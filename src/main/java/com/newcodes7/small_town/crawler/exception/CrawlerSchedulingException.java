package com.newcodes7.small_town.crawler.exception;

public class CrawlerSchedulingException extends CrawlerException {
    
    public CrawlerSchedulingException(String message) {
        super("CRAWLER_SCHEDULING_ERROR", "Crawler scheduling error: %s", message);
    }
    
    public CrawlerSchedulingException(String message, Throwable cause) {
        super("CRAWLER_SCHEDULING_FAILED", "Crawler scheduling failed: %s", cause, message);
    }
    
    public static CrawlerSchedulingException jobAlreadyRunning(String jobName) {
        return new CrawlerSchedulingException("CRAWLER_JOB_ALREADY_RUNNING", "Crawler job '%s' is already running", jobName);
    }
    
    public static CrawlerSchedulingException cronExpressionInvalid(String cronExpression, Throwable cause) {
        return new CrawlerSchedulingException("CRAWLER_CRON_INVALID", "Invalid cron expression: %s", cause, cronExpression);
    }
    
    public static CrawlerSchedulingException schedulerShutdown() {
        return new CrawlerSchedulingException("CRAWLER_SCHEDULER_SHUTDOWN", "Crawler scheduler has been shut down");
    }
    
    public static CrawlerSchedulingException resourceExhaustion(int activeThreads, int maxThreads) {
        return new CrawlerSchedulingException("CRAWLER_RESOURCE_EXHAUSTION", "Resource exhaustion: %d active threads out of %d maximum", activeThreads, maxThreads);
    }
    
    public static CrawlerSchedulingException concurrentExecutionLimit(String jobName, int maxConcurrency) {
        return new CrawlerSchedulingException("CRAWLER_CONCURRENT_LIMIT", "Concurrent execution limit reached for job '%s' (max: %d)", jobName, maxConcurrency);
    }
    
    public static CrawlerSchedulingException jobExecutionFailed(String jobName, Throwable cause) {
        return new CrawlerSchedulingException("CRAWLER_JOB_EXECUTION_FAILED", "Job execution failed for '%s'", cause, jobName);
    }
    
    private CrawlerSchedulingException(String errorCode, String message, Object... args) {
        super(errorCode, message, args);
    }
    
    private CrawlerSchedulingException(String errorCode, String message, Throwable cause, Object... args) {
        super(errorCode, message, cause, args);
    }
}