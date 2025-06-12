package com.newcodes7.small_town.crawler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "crawler")
@Data
public class CrawlerProperties {
    private boolean enabled = true;
    private Schedule schedule = new Schedule();
    private Timeout timeout = new Timeout();
    private Retry retry = new Retry();
    private Concurrent concurrent = new Concurrent();
    
    @Data
    public static class Schedule {
        private String cron = "0 0 2 * * ?"; // 매일 새벽 2시
    }
    
    @Data
    public static class Timeout {
        private int seconds = 30;
    }
    
    @Data
    public static class Retry {
        private int maxAttempts = 3;
    }
    
    @Data
    public static class Concurrent {
        private int maxThreads = 5;
    }
}