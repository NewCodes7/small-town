package com.newcodes7.small_town.global.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("corporationArticles");
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .maximumWeight(100_000_000)  // 약 100MB 제한 (가중치 기준)
            .weigher((Weigher<Object, Object>) (key, value) -> {
                // 키와 값의 대략적인 크기 추정 (바이트 단위)
                int keySize = key.toString().length() * 2; // char는 2바이트
                int valueSize = estimateSize(value);
                return keySize + valueSize;
            })
            .recordStats());  // 캐시 통계 수집
        return cacheManager;
    }

    private int estimateSize(Object value) {
        // 값의 크기를 대략적으로 추정
        if (value == null) return 0;

        String str = value.toString();
        // 문자열 길이 * 2 (char는 2바이트) + 객체 오버헤드 약 50바이트
        return (str.length() * 2) + 50;
    }
}
