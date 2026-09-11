/* Voice bidding — the auctioneer speaks; the console auto-updates the bid.
 * ---------------------------------------------------------------------------
 * WHAT the auctioneer can say (English / Indian numbering, en-IN):
 *   - An AMOUNT and/or a TEAM, in either order, across separate phrases:
 *       "fifty thousand"           → raise to ₹50,000, team logo hidden
 *       "... Warriors"             → attribute the pending amount to the team
 *       "seventy five Warriors"    → both at once
 *   - Bare numbers are SCALED to the current bid's magnitude (see below):
 *       prev ₹70,000, say "75"     → ₹75,000
 *       prev ₹3.5L,   say "350"    → ₹3,50,000
 *   - "sold confirm"               → confirm the sale to the leading team
 *   - "unsold confirm"             → mark the player unsold
 *   - "stop listening" / "pause"   → pause acting on speech (mic stays on)
 *   - "start listening" / "resume" → resume acting on speech
 *   - "cancel" / "clear"           → drop a mis-heard pending amount
 *
 * RAPID BIDDING (buffer): every recognized phrase is pushed onto a QUEUE and
 * applied ONE AT A TIME by an async worker. So when several teams bid in quick
 * succession the phrases don't race — they buffer, apply in order, and the
 * on-screen amount/leader catch up as the queue drains. When bidding slows the
 * queue empties and the 2-second dashboard poll leaves every screen in sync.
 *
 * CONTEXT-AWARE SCALING: a bare number (no "thousand/lakh/crore") is aligned to
 * the CURRENT bid's number of digits, so "75" after ₹70,000 becomes ₹75,000 and
 * "350" after ₹3,50,000 becomes ₹3,50,000. Say an explicit unit to jump across
 * magnitudes ("one lakh"). The reference amount is the freshest we know — the
 * amount we last applied locally, else the live pending/current/base price.
 *
 * How it drives the real auction: a recognized amount is the NEW TOTAL bid;
 * once a team is known we place a genuine bid via /place-bid (purse/feasibility/
 * confirm-sale all apply). Amount-only is parked via /verbal-bid so the audience
 * broadcast screen shows the rising amount with the logo hidden.
 *
 * Runs only on the auctioneer's console. Web Speech API (Chrome/Edge). Reads
 * shared globals from app.js: lastBlock, lastTeams, post(), refresh(), toast(),
 * fmtINR(). WHERE IT CAN FAIL: recognition is best-effort (accents/noise); the
 * worst case is a wrong pending amount fixed by re-saying it, "cancel", or the
 * on-screen Undo/Manual controls. Nothing here is irreversible on its own —
 * confirm-sale/mark-unsold hit the same guarded endpoints as the buttons.
 */
(function () {
  'use strict';

  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;

  const el = {
    wrap:   document.getElementById('voice-bid'),
    toggle: document.getElementById('vb-toggle'),
    status: document.getElementById('vb-status'),
    heard:  document.getElementById('vb-heard'),
    help:   document.getElementById('voice-help'),
  };
  if (!el.wrap || !el.toggle) return;
  const showHelp = () => { if (el.help) el.help.style.display = 'block'; };

  if (!SR) {
    el.wrap.style.display = 'flex';
    el.toggle.disabled = true;
    el.toggle.textContent = '🎙 Voice N/A';
    el.status.textContent = 'Use Chrome or Edge for voice bidding';
    showHelp();
    return;
  }
  el.wrap.style.display = 'flex';
  showHelp();

  // ── number parsing ───────────────────────────────────────────────────────
  const SMALL = {
    zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7,
    eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, thirteen: 13,
    fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18,
    nineteen: 19, twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60,
    seventy: 70, eighty: 80, ninety: 90,
  };
  // "Big" scale words — their presence means the amount is EXPLICIT (no scaling).
  const MAG = { thousand: 1e3, k: 1e3, lakh: 1e5, lac: 1e5, lakhs: 1e5, crore: 1e7, cr: 1e7, crores: 1e7 };

  /**
   * Parse a spoken amount. Returns { value, hadBigUnit } in whole rupees, or
   * null if no number is present. hadBigUnit is true when a thousand/lakh/crore
   * word was used, meaning the value is explicit and must NOT be context-scaled.
   * The recognizer usually returns compound numbers as digits ("350"), so the
   * word path is mostly a fallback.
   */
  function parseAmount(text) {
    const words = text.toLowerCase().replace(/,/g, '').replace(/[-]/g, ' ').split(/\s+/);
    let total = 0, current = 0, found = false, hadBigUnit = false;
    for (const w of words) {
      if (w === '') continue;
      if (/^\d+(\.\d+)?$/.test(w)) { current += parseFloat(w); found = true; continue; }
      if (SMALL[w] != null) { current += SMALL[w]; found = true; continue; }
      if (w === 'hundred' || w === 'hundreds') { current = (current || 1) * 100; found = true; continue; }
      if (MAG[w] != null) { current = (current || 1) * MAG[w]; total += current; current = 0; found = true; hadBigUnit = true; continue; }
    }
    total += current;
    return found ? { value: Math.round(total), hadBigUnit } : null;
  }

  /** Numeric value of a single token ("220" or "fifty"), or null if not a number. */
  function tokenNumber(w) {
    if (/^\d+(\.\d+)?$/.test(w)) return parseFloat(w);
    return SMALL[w] != null ? SMALL[w] : null;
  }

  /**
   * Split an utterance into SEPARATE spoken amounts. The auctioneer often
   * rattles off several bids in one breath ("220 240 260" or "220 and 240").
   * parseAmount would SUM those into one wrong number, so we first segment.
   *
   * The rule: a new amount starts when a number token does NOT continue the
   * current one in place-value terms. A token continues only if it is strictly
   * SMALLER than the running magnitude (a lower-order term, e.g. the "fifty" in
   * "…lakh fifty thousand", or the unit in "twenty five"); anything equal or
   * larger ("240" after "220", "eighty" after "seventy five") begins a new
   * amount. Scale words (hundred/thousand/lakh/crore) set the running magnitude
   * so their following lower-order terms stay attached. Connector/team words
   * ("and", "Warriors") don't split and don't move the magnitude — so
   * "two hundred and fifty" stays 250 while "220 and 240" splits into 220, 240.
   *
   * Returns segments [{ amt: {value,hadBigUnit}|null, text }] in spoken order.
   */
  function amountSegments(text) {
    const words = text.toLowerCase().replace(/,/g, '').replace(/[-]/g, ' ').split(/\s+/).filter(Boolean);
    const groups = [];
    let cur = [], lastMag = null, scaleMin = null, trailStart = 0, sealed = false;
    const flush = () => {
      if (cur.length) groups.push(cur.join(' '));
      cur = []; lastMag = null; scaleMin = null; trailStart = 0; sealed = false;
    };
    for (const w of words) {
      const n = tokenNumber(w);
      if (n != null) {
        if (cur.length && lastMag != null && !(n < lastMag)) flush();  // e.g. "240" after "220"
        if (sealed) { trailStart = cur.length; sealed = false; }       // start of a fresh sub-number after a scale word
        cur.push(w); lastMag = n;
      } else if (w === 'hundred' || w === 'hundreds') {
        cur.push(w); lastMag = 100;                                    // "hundred" is not a big unit; keep it in the number
      } else if (MAG[w] != null) {
        const M = MAG[w];
        // A repeated or ascending big unit ("thousand … thousand", "thousand … lakh")
        // can't be a lower-order continuation → the trailing number is a NEW amount.
        if (scaleMin != null && M >= scaleMin) {
          const completed = cur.slice(0, trailStart);
          const tail = cur.slice(trailStart);
          if (completed.length) groups.push(completed.join(' '));
          cur = tail; scaleMin = null; trailStart = 0;
        }
        cur.push(w); scaleMin = scaleMin == null ? M : Math.min(scaleMin, M); lastMag = M; sealed = true;
      } else {
        cur.push(w);   // connector / team word: keep, but don't split or move magnitude
      }
    }
    flush();
    return groups.map(g => ({ amt: parseAmount(g), text: g }));
  }

  const digitCount = n => String(Math.max(1, Math.round(Math.abs(n)))).length;

  /**
   * Align a bare number to the reference amount's magnitude by multiplying by a
   * power of ten so it has the same digit count. "75" vs ₹70,000 → 75,000;
   * "350" vs ₹3,50,000 → 3,50,000. If the number already has >= the reference's
   * digits, it is taken as-is (a full amount was spoken).
   */
  function scaleToContext(value, reference) {
    if (!reference || reference <= 0) return value;
    const k = digitCount(reference) - digitCount(value);
    return k > 0 ? value * Math.pow(10, k) : value;
  }

  // ── team matching ─────────────────────────────────────────────────────────
  const lettersOnly = s => String(s || '').toLowerCase().replace(/[^a-z\s]/g, ' ');

  function parseTeam(text) {
    const said = ' ' + lettersOnly(text) + ' ';
    let best = null, bestScore = 0;
    for (const t of (typeof lastTeams !== 'undefined' ? lastTeams : [])) {
      for (const w of lettersOnly(t.name).split(/\s+/).filter(w => w.length >= 3)) {
        if (said.includes(' ' + w + ' ') && w.length > bestScore) { bestScore = w.length; best = t.teamId; }
      }
    }
    return best;
  }

  // Keywords that put a player on the block, e.g. "next player Virat Kohli".
  const BRING_RE = /\b(next player|next up|up next|bring up|bring in|bring on|call up|put up|on the block|nominate)\b/;

  /**
   * Match a spoken name to an AVAILABLE player in the pool (best keyword overlap).
   * Returns { playerId, name } or null. Only AVAILABLE players can be put on the
   * block, so SOLD/UNSOLD ones are skipped.
   */
  function parsePlayer(text) {
    const said = ' ' + lettersOnly(text) + ' ';
    let best = null, bestName = null, bestScore = 0;
    for (const p of (typeof lastPlayers !== 'undefined' ? lastPlayers : [])) {
      if (p.status && p.status !== 'AVAILABLE') continue;
      for (const w of lettersOnly(p.name).split(/\s+/).filter(w => w.length >= 3)) {
        if (said.includes(' ' + w + ' ') && w.length > bestScore) { bestScore = w.length; best = p.playerId; bestName = p.name; }
      }
    }
    return best ? { playerId: best, name: bestName } : null;
  }

  // ── shared helpers ────────────────────────────────────────────────────────
  const currentBlock = () => (typeof lastBlock !== 'undefined') ? lastBlock : null;

  // Reconciliation + scaling state. runningAmount is the freshest amount we've
  // applied locally — used as the scaling reference so rapid bare numbers line
  // up even before the 2s dashboard poll refreshes lastBlock.
  const state = { playerId: null, pendingTeamId: null, pendingAmount: null, runningAmount: null };

  function resetPending() { state.pendingTeamId = null; state.pendingAmount = null; }
  function syncPlayer(block) {
    const pid = block ? block.playerId : null;
    if (pid !== state.playerId) { state.playerId = pid; resetPending(); state.runningAmount = null; }
  }
  function referenceAmount() {
    if (state.runningAmount != null) return state.runningAmount;
    const b = currentBlock();
    if (!b) return null;
    return b.pendingBidAmount != null ? b.pendingBidAmount
         : b.currentBidAmount != null ? b.currentBidAmount
         : b.basePrice;
  }

  // ── server actions ────────────────────────────────────────────────────────
  async function placeBid(playerId, teamId, amount) {
    const r = await post(`/api/admin/players/${playerId}/place-bid`, { teamId, amount });
    if (r) {
      state.runningAmount = r.currentBidAmount;
      resetPending();
      toast(`🎙 Bid #${r.bidNumber}: ${r.currentLeadingTeamName} → ${fmtINR(r.currentBidAmount)}`);
      refresh();
    }
    return r;   // null on failure (post already toasted) — callers gate the sale on this
  }
  async function setVerbalBid(playerId, amount) {
    const r = await post(`/api/admin/players/${playerId}/verbal-bid`, { amount });
    if (r) { toast(`🎙 ${fmtINR(amount)} — awaiting team…`); refresh(); }
  }
  async function clearVerbal(playerId) {
    resetPending();
    await post(`/api/admin/players/${playerId}/clear-verbal-bid`);
    toast('🎙 Pending bid cleared'); refresh();
  }
  async function sell(playerId) {
    const r = await post(`/api/admin/players/${playerId}/confirm-sale`);
    if (r) {
      resetPending(); state.runningAmount = null;
      const p = r.player || {};
      toast(`🔨 SOLD! ${p.name || ''}${p.soldPrice ? ' → ' + fmtINR(p.soldPrice) : ''}`);
      refresh();
    }
  }
  async function unsold(playerId) {
    const r = await post(`/api/admin/players/${playerId}/mark-unsold`);
    if (r) { resetPending(); state.runningAmount = null; toast(`🚫 UNSOLD: ${r.name || ''}`); refresh(); }
  }
  async function bringOnBlock(playerId, spokenName) {
    if (!playerId) { toast(`Couldn't find an available player matching “${spokenName}”`, true); return; }
    const r = await post(`/api/admin/players/${playerId}/mark-under-auction`);
    if (r) { resetPending(); state.runningAmount = null; toast(`🎯 On the block: ${r.name || ''}`); refresh(); }
  }
  /**
   * "SOLD to <team> at <amount>": make that team the winning bid, then sell.
   * If the team already leads, just confirm (a team can't outbid itself); if the
   * bid can't be placed (e.g. purse), post() toasts and we do NOT sell.
   */
  async function doSold(block, teamId, amount) {
    if (teamId && block.currentLeadingTeamId !== teamId) {
      const r = await placeBid(block.playerId, teamId, amount);   // amount may be null → server increment
      if (!r) return;
    }
    await sell(block.playerId);
  }

  // ── async queue (the rapid-bid buffer) ────────────────────────────────────
  const queue = [];
  let draining = false;
  function enqueue(intent) { queue.push(intent); if (!draining) drain(); }
  async function drain() {
    draining = true;
    try {
      while (queue.length) {
        const it = queue.shift();
        try { await handleIntent(it); } catch (_) { /* post() already toasts */ }
      }
    } finally { draining = false; }
  }

  async function handleIntent(it) {
    // "On the block" SETS the current player, so it runs even when none is live.
    if (it.kind === 'onblock') { await bringOnBlock(it.playerId, it.playerName); return; }

    const block = currentBlock();
    if (!block) { toast('No player is under auction — say “next player <name>” to put one up', true); return; }
    syncPlayer(block);

    if (it.kind === 'clear')  { await clearVerbal(block.playerId); return; }
    if (it.kind === 'unsold') { await unsold(block.playerId); return; }
    if (it.kind === 'sold') {
      const amount = it.amountRaw == null ? null
          : (it.hadBigUnit ? it.amountRaw : scaleToContext(it.amountRaw, referenceAmount()));
      await doSold(block, it.teamId, amount);
      return;
    }

    // kind === 'bid': scale the amount now, against the freshest reference.
    const amount = it.amountRaw == null ? null
        : (it.hadBigUnit ? it.amountRaw : scaleToContext(it.amountRaw, referenceAmount()));
    const effTeam = it.teamId || state.pendingTeamId;
    const effAmount = amount != null ? amount
        : (state.pendingAmount != null ? state.pendingAmount : block.pendingBidAmount);

    if (effAmount != null && effTeam) {
      if (amount != null) state.runningAmount = amount;
      await placeBid(block.playerId, effTeam, effAmount);
    } else if (amount != null) {
      state.pendingAmount = amount;
      state.runningAmount = amount;
      await setVerbalBid(block.playerId, amount);
    } else if (it.teamId) {
      state.pendingTeamId = it.teamId;
      const name = (lastTeams.find(t => t.teamId === it.teamId) || {}).name || 'Team';
      toast(`🎙 ${name} noted — say the bid amount`);
    }
  }

  // ── classify one utterance into ordered intents (or a control command) ────
  // Returns an ARRAY: control commands yield one intent; a phrase with several
  // amounts ("220 240") yields one bid intent PER amount, so they stream into
  // the queue and apply one by one instead of being summed.
  function classifyToIntents(text) {
    const t = text.toLowerCase();
    // Order matters: "unsold" contains "sold", so test unsold first. \bsold\b does
    // NOT match inside "unsold" (no word boundary), so the two never collide.
    if (/\bunsold\b/.test(t)) return [{ kind: 'unsold' }];                       // "UNSOLD"
    if (/\bsold\b/.test(t)) {                                                     // "SOLD to <team> at <amount>"
      const amt = parseAmount(text);
      return [{ kind: 'sold', teamId: parseTeam(text),
                amountRaw: amt ? amt.value : null, hadBigUnit: amt ? amt.hadBigUnit : false }];
    }
    if (BRING_RE.test(t)) {                                                       // "next player <name>", etc.
      const name = text.replace(BRING_RE, ' ').trim();
      const p = parsePlayer(name);
      return [{ kind: 'onblock', playerId: p ? p.playerId : null, playerName: name }];
    }
    if (/\b(cancel|clear|reset|scratch)\b/.test(t))    return [{ kind: 'clear' }];

    const amtSegs = amountSegments(text).filter(s => s.amt != null);
    if (amtSegs.length <= 1) {
      // Zero or one amount: keep the whole-utterance team (e.g. "seventy five Warriors").
      const amt = amtSegs.length ? amtSegs[0].amt : null;
      const teamId = parseTeam(text);
      if (amt == null && teamId == null) return [];
      return [{ kind: 'bid', amountRaw: amt ? amt.value : null, hadBigUnit: amt ? amt.hadBigUnit : false, teamId }];
    }
    // Multiple amounts: one bid each, with any team named within that segment.
    return amtSegs.map(s => ({
      kind: 'bid', amountRaw: s.amt.value, hadBigUnit: s.amt.hadBigUnit, teamId: parseTeam(s.text),
    }));
  }

  // ── Web Speech API wiring ─────────────────────────────────────────────────
  let micOn = false;   // recognition running (needs a click to start — gesture)
  let armed = false;   // acting on speech (voice can pause/resume without a click)
  const rec = new SR();
  rec.lang = 'en-IN';
  rec.continuous = true;
  rec.interimResults = true;

  rec.onresult = (e) => {
    let interim = '';
    for (let i = e.resultIndex; i < e.results.length; i++) {
      const res = e.results[i];
      if (res.isFinal) {
        const text = res[0].transcript.trim();
        el.heard.textContent = '“' + text + '”';
        const t = text.toLowerCase();
        // Arm/pause commands work even while paused (so voice can re-arm).
        if (/\b(stop|pause) listening\b/.test(t) || /\bgo to sleep\b/.test(t)) { setArmed(false); continue; }
        if (/\b(start|resume) listening\b/.test(t) || /\bwake up\b/.test(t))   { setArmed(true); continue; }
        if (!armed) continue;                 // paused: ignore bids/commands
        for (const intent of classifyToIntents(text)) enqueue(intent);
      } else {
        interim += res[0].transcript;
      }
    }
    if (interim) el.heard.textContent = '“' + interim.trim() + '”…';
  };

  rec.onerror = (e) => {
    if (e.error === 'no-speech' || e.error === 'aborted') return;
    if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
      toast('Microphone blocked — allow mic access to use voice bidding', true);
      stopMic();
      return;
    }
    toast('Voice error: ' + e.error, true);
  };
  rec.onend = () => { if (micOn) { try { rec.start(); } catch (_) {} } };

  function updateUI() {
    el.toggle.classList.toggle('listening', micOn && armed);
    el.toggle.classList.toggle('paused', micOn && !armed);
    if (!micOn) {
      el.toggle.textContent = '🎙 Listen';
      el.status.textContent = 'Voice off';
      el.status.classList.add('muted');
      el.heard.textContent = '';
    } else if (armed) {
      el.toggle.textContent = '🔴 Stop';
      el.status.textContent = 'Listening — say a bid or a command (see list)';
      el.status.classList.remove('muted');
    } else {
      el.toggle.textContent = '⏸ Paused';
      el.status.textContent = 'Paused — say “start listening” to resume (click to stop)';
      el.status.classList.remove('muted');
    }
  }

  function startMic() { try { rec.start(); } catch (_) {} micOn = true; armed = true; updateUI(); }
  function stopMic()  { micOn = false; try { rec.stop(); } catch (_) {} updateUI(); }
  function setArmed(v) {
    if (!micOn || armed === v) return;
    armed = v;
    updateUI();
    toast(v ? '🎙 Listening resumed' : '🎙 Paused');
  }

  el.toggle.addEventListener('click', () => (micOn ? stopMic() : startMic()));
  updateUI();
})();
