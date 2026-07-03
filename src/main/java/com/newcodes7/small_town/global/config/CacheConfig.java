package com.newcodes7.small_town.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(
            buildWeightBasedCache("corporationArticles"),
            buildWeightBasedCache("corporationVideos"),
            buildTtlCache("weeklyPopularArticles", 5, TimeUnit.MINUTES),
            buildTtlCache("relatedArticles", 24, TimeUnit.HOURS),
            buildTtlCache("aiSummary", 1, TimeUnit.HOURS),
            buildTtlCache("chunkSearchResults", 5, TimeUnit.MINUTES),
            buildTtlCache("vectorSearchResults", 5, TimeUnit.MINUTES, 500),
            buildTtlCache("hybridTopArticles", 10, TimeUnit.MINUTES, 1000),
            buildTtlCache("homeLatestArticles", 10, TimeUnit.MINUTES),
            buildTtlCache("homeLatestVideos", 10, TimeUnit.MINUTES)
        ));
        return cacheManager;
    }

    private CaffeineCache buildWeightBasedCache(String name) {
        return new CaffeineCache(name, Caffeine.newBuilder()
            .maximumWeight(100_000_000)
            .weigher((Weigher<Object, Object>) (key, value) -> {
                int keySize = key.toString().length() * 2;
                int valueSize = estimateSize(value);
                return keySize + valueSize;
            })
            .recordStats()
            .build());
    }

    private CaffeineCache buildTtlCache(String name, long duration, TimeUnit unit) {
        return new CaffeineCache(name, Caffeine.newBuilder()
            .expireAfterWrite(duration, unit)
            .recordStats()
            .build());
    }

    private CaffeineCache buildTtlCache(String name, long duration, TimeUnit unit, long maximumSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
            .expireAfterWrite(duration, unit)
            .maximumSize(maximumSize)
            .recordStats()
            .build());
    }

    private int estimateSize(Object value) {
        if (value == null) return 0;
        String str = value.toString();
        return (str.length() * 2) + 50;
    }
}
