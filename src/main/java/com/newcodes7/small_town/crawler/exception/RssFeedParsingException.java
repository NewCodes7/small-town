package com.newcodes7.small_town.crawler.exception;

public class RssFeedParsingException extends CrawlerException {
    
    public RssFeedParsingException(String feedUrl, String message) {
        super("RSS_FEED_PARSING_FAILED", "RSS feed parsing failed for URL: %s. %s", feedUrl, message);
    }
    
    public RssFeedParsingException(String feedUrl, Throwable cause) {
        super("RSS_FEED_PARSING_ERROR", "RSS feed parsing error for URL: %s", cause, feedUrl);
    }
    
    public static RssFeedParsingException invalidFeedFormat(String feedUrl) {
        return new RssFeedParsingException("RSS_FEED_INVALID_FORMAT", "Invalid RSS/Atom feed format for URL: %s", feedUrl);
    }
    
    public static RssFeedParsingException feedNotFound(String feedUrl) {
        return new RssFeedParsingException("RSS_FEED_NOT_FOUND", "RSS feed not found for URL: %s", feedUrl);
    }
    
    public static RssFeedParsingException emptyFeed(String feedUrl) {
        return new RssFeedParsingException("RSS_FEED_EMPTY", "RSS feed contains no items for URL: %s", feedUrl);
    }
    
    public static RssFeedParsingException xmlParsingError(String feedUrl, Throwable cause) {
        return new RssFeedParsingException("RSS_FEED_XML_PARSING_ERROR", "XML parsing error for RSS feed URL: %s", cause, feedUrl);
    }
    
    public static RssFeedParsingException itemParsingError(String feedUrl, int itemIndex, String itemTitle, Throwable cause) {
        return new RssFeedParsingException("RSS_FEED_ITEM_PARSING_ERROR", "Failed to parse RSS feed item #%d ('%s') from URL: %s", cause, itemIndex, itemTitle, feedUrl);
    }
    
    public static RssFeedParsingException unsupportedFeedVersion(String feedUrl, String version) {
        return new RssFeedParsingException("RSS_FEED_UNSUPPORTED_VERSION", "Unsupported RSS/Atom feed version '%s' for URL: %s", version, feedUrl);
    }
    
    private RssFeedParsingException(String errorCode, String message, Object... args) {
        super(errorCode, message, args);
    }
    
    private RssFeedParsingException(String errorCode, String message, Throwable cause, Object... args) {
        super(errorCode, message, cause, args);
    }
}