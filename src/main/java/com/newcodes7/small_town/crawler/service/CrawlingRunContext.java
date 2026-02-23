package com.newcodes7.small_town.crawler.service;

import org.springframework.stereotype.Component;

@Component
public class CrawlingRunContext {
    private final ThreadLocal<Long> runIdHolder = new ThreadLocal<>();

    public void setRunId(Long runId) {
        runIdHolder.set(runId);
    }

    public Long getRunId() {
        return runIdHolder.get();
    }

    public void clear() {
        runIdHolder.remove();
    }
}
