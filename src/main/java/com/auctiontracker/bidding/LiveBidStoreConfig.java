package com.auctiontracker.bidding;

import com.auctiontracker.core.AuctionLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Picks the live-bid store from configuration:
 * <ul>
 *   <li>{@code auction.live.redis-enabled=true} → {@link RedisLiveBidStore} (shared
 *       Redis; required for running more than one app instance). Pair it with
 *       {@code spring.data.redis.url} and {@code spring.session.store-type=redis}
 *       so logins are shared too.</li>
 *   <li>otherwise → {@link InMemoryLiveBidStore} (single instance; the default, and
 *       no Redis connection is ever opened).</li>
 * </ul>
 */
@Configuration
public class LiveBidStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "auction.live.redis-enabled", havingValue = "true")
    LiveBidStore redisLiveBidStore(StringRedisTemplate redis) {
        return new RedisLiveBidStore(redis);
    }

    @Bean
    @ConditionalOnProperty(name = "auction.live.redis-enabled", havingValue = "false", matchIfMissing = true)
    LiveBidStore inMemoryLiveBidStore(AuctionLock lock) {
        return new InMemoryLiveBidStore(lock);
    }
}
