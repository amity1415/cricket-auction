package com.auctiontracker.bidding;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared-Redis {@link LiveBidStore} for horizontally-scaled deployments: the
 * on-block player, the bid trail and the auto-close deadline live in Redis, so
 * every app instance behind the load balancer reads and writes the same state.
 *
 * <p>The correctness-critical operations run as atomic Lua scripts on the Redis
 * server, so they are indivisible across instances:
 * <ul>
 *   <li>{@link #tryPush} re-checks the current leader and appends only if the bid
 *       still wins — so two owners on two instances committing the same amount
 *       resolve to exactly one winner (first script to run holds it; the other
 *       gets {@code TOO_LOW}).</li>
 *   <li>{@link #claimExpiredClose} deletes the deadline as it hands the lot over,
 *       so the auto-close fires once cluster-wide, not once per instance.</li>
 * </ul>
 *
 * A trail entry is stored as {@code "teamId|amount|epochMillis"} (a team id is a
 * UUID and carries no {@code |}, so the split is unambiguous). Like the in-memory
 * store, nothing here is durable: bids persist to the database on sale/unsold.
 */
public class RedisLiveBidStore implements LiveBidStore {

    private static final String PREFIX = "lb:";

    private final StringRedisTemplate redis;

    public RedisLiveBidStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ---- key helpers ------------------------------------------------------
    private static String tid(UUID tournamentId) {
        return tournamentId == null ? "0" : tournamentId.toString();
    }
    private String playerKey(UUID t) { return PREFIX + tid(t) + ":p"; }
    private String trailKey(UUID t)  { return PREFIX + tid(t) + ":t"; }
    private String verbalKey(UUID t) { return PREFIX + tid(t) + ":v"; }
    private String deadlineKey(UUID t) { return PREFIX + tid(t) + ":d"; }

    private static String secs(Integer timerSeconds) {
        return (timerSeconds != null && timerSeconds > 0) ? String.valueOf(timerSeconds) : "0";
    }
    private static String now() { return String.valueOf(System.currentTimeMillis()); }

    private static Step parse(String entry) {
        if (entry == null) {
            return null;
        }
        int a = entry.indexOf('|');
        int b = entry.indexOf('|', a + 1);
        UUID team = UUID.fromString(entry.substring(0, a));
        long amount = Long.parseLong(entry.substring(a + 1, b));
        long ts = Long.parseLong(entry.substring(b + 1));
        return new Step(team, amount, Instant.ofEpochMilli(ts));
    }

    // ---- Lua scripts (atomic on the Redis server) -------------------------
    /** KEYS: player, trail, deadline; ARGV: playerId, teamId, amount, basePrice, timerSeconds, nowMillis. */
    private static final RedisScript<List> TRY_PUSH = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return {'NOT_LIVE',''} end
            local n = redis.call('LLEN', KEYS[2])
            local amount = tonumber(ARGV[3])
            if n == 0 then
              if amount < tonumber(ARGV[4]) then return {'BELOW_BASE',''} end
            else
              local last = redis.call('LINDEX', KEYS[2], -1)
              local lt, la = last:match('([^|]+)|([^|]+)|')
              if lt == ARGV[2] then return {'SELF_OUTBID', la} end
              if amount <= tonumber(la) then return {'TOO_LOW', la} end
            end
            redis.call('RPUSH', KEYS[2], ARGV[2]..'|'..ARGV[3]..'|'..ARGV[6])
            if tonumber(ARGV[5]) > 0 then
              redis.call('SET', KEYS[3], tostring(tonumber(ARGV[6]) + tonumber(ARGV[5]) * 1000))
            else
              redis.call('DEL', KEYS[3])
            end
            return {'OK', tostring(redis.call('LLEN', KEYS[2]))}
            """, List.class);

    /** KEYS: player, trail, verbal, deadline; ARGV: playerId, timerSeconds, nowMillis. */
    private static final RedisScript<Long> OPEN = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1])
            redis.call('DEL', KEYS[2])
            redis.call('DEL', KEYS[3])
            if tonumber(ARGV[2]) > 0 then
              redis.call('SET', KEYS[4], tostring(tonumber(ARGV[3]) + tonumber(ARGV[2]) * 1000))
            else
              redis.call('DEL', KEYS[4])
            end
            return 1
            """, Long.class);

    /** KEYS: player, trail, verbal, deadline; ARGV: playerId, timerSeconds, nowMillis. */
    private static final RedisScript<Long> ENSURE_OPEN = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[1])
              redis.call('DEL', KEYS[2])
              redis.call('DEL', KEYS[3])
              if tonumber(ARGV[2]) > 0 then
                redis.call('SET', KEYS[4], tostring(tonumber(ARGV[3]) + tonumber(ARGV[2]) * 1000))
              else
                redis.call('DEL', KEYS[4])
              end
            end
            return 1
            """, Long.class);

    /** KEYS: player, trail, verbal, deadline; ARGV: playerId. */
    private static final RedisScript<Long> CLOSE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4])
            end
            return 1
            """, Long.class);

    /** KEYS: player, trail, deadline; ARGV: playerId, timerSeconds, nowMillis. */
    private static final RedisScript<String> POP_LAST = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return false end
            local v = redis.call('RPOP', KEYS[2])
            if v then
              if tonumber(ARGV[2]) > 0 then
                redis.call('SET', KEYS[3], tostring(tonumber(ARGV[3]) + tonumber(ARGV[2]) * 1000))
              else
                redis.call('DEL', KEYS[3])
              end
            end
            return v
            """, String.class);

    /** KEYS: player, trail, verbal, deadline; ARGV: playerId. Returns
     *  {live, lastEntry, count, verbal, deadline} in one round-trip. */
    private static final RedisScript<List> SNAPSHOT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return {'0','','0','',''} end
            local n = redis.call('LLEN', KEYS[2])
            local last = ''
            if n > 0 then last = redis.call('LINDEX', KEYS[2], -1) end
            local v = redis.call('GET', KEYS[3])
            local d = redis.call('GET', KEYS[4])
            return {'1', last, tostring(n), v or '', d or ''}
            """, List.class);

    /** KEYS: player, deadline; ARGV: nowMillis. */
    private static final RedisScript<String> CLAIM = new DefaultRedisScript<>("""
            local p = redis.call('GET', KEYS[1])
            if not p then return false end
            local d = redis.call('GET', KEYS[2])
            if not d then return false end
            if tonumber(ARGV[1]) < tonumber(d) then return false end
            redis.call('DEL', KEYS[2])
            return p
            """, String.class);

    // ---- operations -------------------------------------------------------
    @Override
    public UUID currentPlayer(UUID t) {
        String p = redis.opsForValue().get(playerKey(t));
        return p == null ? null : UUID.fromString(p);
    }

    @Override
    public boolean isFor(UUID t, UUID playerId) {
        return playerId != null && playerId.toString().equals(redis.opsForValue().get(playerKey(t)));
    }

    @Override
    public void open(UUID t, UUID playerId, Integer timerSeconds) {
        redis.execute(OPEN, List.of(playerKey(t), trailKey(t), verbalKey(t), deadlineKey(t)),
                playerId.toString(), secs(timerSeconds), now());
    }

    @Override
    public void ensureOpen(UUID t, UUID playerId, Integer timerSeconds) {
        redis.execute(ENSURE_OPEN, List.of(playerKey(t), trailKey(t), verbalKey(t), deadlineKey(t)),
                playerId.toString(), secs(timerSeconds), now());
    }

    @Override
    public void close(UUID t, UUID playerId) {
        redis.execute(CLOSE, List.of(playerKey(t), trailKey(t), verbalKey(t), deadlineKey(t)),
                playerId.toString());
    }

    @Override
    @SuppressWarnings("unchecked")
    public PushOutcome tryPush(UUID t, UUID playerId, UUID teamId, long amount,
                               long basePrice, Integer timerSeconds) {
        List<String> r = redis.execute(TRY_PUSH,
                List.of(playerKey(t), trailKey(t), deadlineKey(t)),
                playerId.toString(), teamId.toString(), String.valueOf(amount),
                String.valueOf(basePrice), secs(timerSeconds), now());
        String status = r.get(0);
        String extra = r.size() > 1 ? r.get(1) : "";
        Long extraAmount = (extra == null || extra.isEmpty()) ? null : Long.valueOf(extra);
        return switch (status) {
            case "OK" -> new PushOutcome(PushStatus.OK, extraAmount == null ? 0 : extraAmount.intValue(), amount);
            case "SELF_OUTBID" -> new PushOutcome(PushStatus.SELF_OUTBID, 0, extraAmount);
            case "TOO_LOW" -> new PushOutcome(PushStatus.TOO_LOW, 0, extraAmount);
            case "BELOW_BASE" -> new PushOutcome(PushStatus.BELOW_BASE, 0, null);
            default -> new PushOutcome(PushStatus.NOT_LIVE, 0, null);
        };
    }

    @Override
    public Step popLast(UUID t, UUID playerId, Integer timerSeconds) {
        String v = redis.execute(POP_LAST, List.of(playerKey(t), trailKey(t), deadlineKey(t)),
                playerId.toString(), secs(timerSeconds), now());
        return parse(v);
    }

    @Override
    public Step last(UUID t, UUID playerId) {
        if (!isFor(t, playerId)) {
            return null;
        }
        return parse(redis.opsForList().index(trailKey(t), -1));
    }

    @Override
    public int count(UUID t, UUID playerId) {
        if (!isFor(t, playerId)) {
            return 0;
        }
        Long n = redis.opsForList().size(trailKey(t));
        return n == null ? 0 : n.intValue();
    }

    @Override
    public List<Step> steps(UUID t, UUID playerId) {
        if (!isFor(t, playerId)) {
            return List.of();
        }
        List<String> raw = redis.opsForList().range(trailKey(t), 0, -1);
        if (raw == null) {
            return List.of();
        }
        List<Step> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            out.add(parse(s));
        }
        return out;
    }

    @Override
    public void setPendingVerbal(UUID t, UUID playerId, Long amount) {
        if (!isFor(t, playerId)) {
            return;
        }
        if (amount == null) {
            redis.delete(verbalKey(t));
        } else {
            redis.opsForValue().set(verbalKey(t), String.valueOf(amount));
        }
    }

    @Override
    public Long pendingVerbal(UUID t, UUID playerId) {
        if (!isFor(t, playerId)) {
            return null;
        }
        String v = redis.opsForValue().get(verbalKey(t));
        return v == null ? null : Long.valueOf(v);
    }

    @Override
    public Instant deadline(UUID t, UUID playerId) {
        if (!isFor(t, playerId)) {
            return null;
        }
        String d = redis.opsForValue().get(deadlineKey(t));
        return d == null ? null : Instant.ofEpochMilli(Long.parseLong(d));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Snapshot snapshot(UUID t, UUID playerId) {
        List<String> r = redis.execute(SNAPSHOT,
                List.of(playerKey(t), trailKey(t), verbalKey(t), deadlineKey(t)),
                playerId.toString());
        if (r == null || r.isEmpty() || !"1".equals(r.get(0))) {
            return new Snapshot(false, null, null, 0, null, null);
        }
        Step last = parse(emptyToNull(r.get(1)));
        int count = Integer.parseInt(r.get(2));
        Long verbal = toLong(r.size() > 3 ? r.get(3) : null);
        String d = r.size() > 4 ? emptyToNull(r.get(4)) : null;
        Instant deadline = d == null ? null : Instant.ofEpochMilli(Long.parseLong(d));
        return new Snapshot(true,
                last == null ? null : last.amount(),
                last == null ? null : last.teamId(),
                count, verbal, deadline);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static Long toLong(String s) {
        s = emptyToNull(s);
        return s == null ? null : Long.valueOf(s);
    }

    @Override
    public Optional<UUID> claimExpiredClose(UUID t) {
        String p = redis.execute(CLAIM, List.of(playerKey(t), deadlineKey(t)), now());
        return p == null ? Optional.empty() : Optional.of(UUID.fromString(p));
    }

    @Override
    public void forget(UUID t) {
        redis.delete(List.of(playerKey(t), trailKey(t), verbalKey(t), deadlineKey(t)));
    }
}
