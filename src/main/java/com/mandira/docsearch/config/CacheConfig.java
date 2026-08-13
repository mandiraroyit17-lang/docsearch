package com.mandira.docsearch.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * In-memory cache with a short TTL, standing in for Redis in the prototype.
 * Search results shift as documents are written, so results are cached for
 * 30s only — an explicit staleness-for-throughput trade-off (see README).
 *
 * Swap this bean for a RedisCacheManager in production; nothing else in the
 * codebase changes, since callers only ever see the Spring Cache
 * abstraction (@Cacheable / @CacheEvict).
 */
@Configuration
public class CacheConfig {

    /**
     * Creates and configures a cache manager for search results.
     *
     * Uses Caffeine for in-memory caching with a 30-second TTL and max size of 10,000 entries.
     * This is a prototype implementation; production deployments should swap this
     * for a RedisCacheManager without changing any other code (only Spring Cache
     * abstraction is used throughout).
     *
     * @return a configured CacheManager bean
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("searchResults");
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(10_000));
        return manager;
    }
}
