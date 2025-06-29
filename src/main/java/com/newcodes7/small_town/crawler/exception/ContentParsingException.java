package com.newcodes7.small_town.crawler.exception;

public class ContentParsingException extends CrawlerException {
    
    public ContentParsingException(String url, String element) {
        super("CONTENT_ELEMENT_NOT_FOUND", "Required element '%s' not found in page: %s", element, url);
    }
    
    public ContentParsingException(String url, String element, Throwable cause) {
        super("CONTENT_PARSING_FAILED", "Failed to parse element '%s' from page: %s", cause, element, url);
    }
    
    public static ContentParsingException malformedHtml(String url, Throwable cause) {
        return new ContentParsingException("CONTENT_MALFORMED_HTML", "Malformed HTML structure for URL: %s", cause, url);
    }
    
    public static ContentParsingException encodingError(String url, String encoding, Throwable cause) {
        return new ContentParsingException("CONTENT_ENCODING_ERROR", "Character encoding error (%s) for URL: %s", cause, encoding, url);
    }
    
    public static ContentParsingException emptyContent(String url) {
        return new ContentParsingException("CONTENT_EMPTY", "No content found for URL: %s", url);
    }
    
    public static ContentParsingException dateParsingFailed(String url, String dateString, Throwable cause) {
        return new ContentParsingException("CONTENT_DATE_PARSING_FAILED", "Failed to parse date '%s' from URL: %s", cause, dateString, url);
    }
    
    public static ContentParsingException titleNotFound(String url) {
        return new ContentParsingException("CONTENT_TITLE_NOT_FOUND", "Article title not found for URL: %s", url);
    }
    
    public static ContentParsingException linkNotFound(String url) {
        return new ContentParsingException("CONTENT_LINK_NOT_FOUND", "Article link not found for URL: %s", url);
    }
    
    private ContentParsingException(String errorCode, String message, Object... args) {
        super(errorCode, message, args);
    }
    
    private ContentParsingException(String errorCode, String message, Throwable cause, Object... args) {
        super(errorCode, message, cause, args);
    }
}