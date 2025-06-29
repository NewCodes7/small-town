package com.newcodes7.small_town.global.exception;

import com.newcodes7.small_town.article.exception.ArticleException;
import com.newcodes7.small_town.article.exception.ErrorResponse;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.NoHandlerFoundException;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(NoHandlerFoundException.class)
    public String handleNoHandlerFoundException(NoHandlerFoundException e, Model model, HttpServletRequest request) {
        log.warn("404 Error - 페이지를 찾을 수 없음: {}", request.getRequestURI());
        
        if (isApiRequest(request)) {
            return handleApiNotFoundException(request);
        }
        
        model.addAttribute("status", "404");
        model.addAttribute("error", "페이지를 찾을 수 없습니다");
        model.addAttribute("message", "요청하신 페이지가 존재하지 않습니다.");
        model.addAttribute("path", request.getRequestURI());
        model.addAttribute("timestamp", LocalDateTime.now());
        
        return "error/404";
    }
    
    @ExceptionHandler(Exception.class)
    public String handleGeneralException(Exception e, Model model, HttpServletRequest request) {
        log.error("예상치 못한 오류 발생: {} at {}", e.getMessage(), request.getRequestURI(), e);
        
        if (isApiRequest(request)) {
            return handleApiException(e, request);
        }
        
        model.addAttribute("status", "500");
        model.addAttribute("error", "서버 내부 오류");
        model.addAttribute("message", "죄송합니다. 서버에서 문제가 발생했습니다.");
        model.addAttribute("path", request.getRequestURI());
        model.addAttribute("timestamp", LocalDateTime.now());
        
        return "error/500";
    }
    
    @ExceptionHandler(ArticleException.class)
    public String handleArticleException(ArticleException e, Model model, HttpServletRequest request) {
        log.warn("Article 예외 발생: {} at {}", e.getMessage(), request.getRequestURI());
        
        if (isApiRequest(request)) {
            return handleApiArticleException(e, request);
        }
        
        HttpStatus status = determineHttpStatus(e);
        
        model.addAttribute("status", status.value());
        model.addAttribute("error", getErrorTitle(status));
        model.addAttribute("message", e.getMessage());
        model.addAttribute("path", request.getRequestURI());
        model.addAttribute("timestamp", LocalDateTime.now());
        
        if (status == HttpStatus.NOT_FOUND) {
            return "error/404";
        } else if (status == HttpStatus.FORBIDDEN) {
            return "error/403";
        } else {
            return "error/error";
        }
    }
    
    @ExceptionHandler(CrawlerException.class)
    public String handleCrawlerException(CrawlerException e, Model model, HttpServletRequest request) {
        log.warn("Crawler 예외 발생: {} at {}", e.getMessage(), request.getRequestURI());
        
        if (isApiRequest(request)) {
            return handleApiCrawlerException(e, request);
        }
        
        HttpStatus status = determineCrawlerHttpStatus(e);
        
        model.addAttribute("status", status.value());
        model.addAttribute("error", getErrorTitle(status));
        model.addAttribute("message", e.getMessage());
        model.addAttribute("path", request.getRequestURI());
        model.addAttribute("timestamp", LocalDateTime.now());
        
        if (status == HttpStatus.NOT_FOUND) {
            return "error/404";
        } else if (status == HttpStatus.FORBIDDEN) {
            return "error/403";
        } else if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return "error/429";
        } else {
            return "error/error";
        }
    }
    
    private boolean isApiRequest(HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        String acceptHeader = request.getHeader("Accept");
        
        return requestURI.startsWith("/api/") || 
               (acceptHeader != null && acceptHeader.contains("application/json"));
    }
    
    private String handleApiNotFoundException(HttpServletRequest request) {
        ErrorResponse errorResponse = ErrorResponse.of(
                "NOT_FOUND",
                "요청하신 리소스를 찾을 수 없습니다.",
                request.getRequestURI()
        );
        
        setApiErrorResponse(HttpStatus.NOT_FOUND, errorResponse);
        return "error/404";
    }
    
    private String handleApiException(Exception e, HttpServletRequest request) {
        ErrorResponse errorResponse = ErrorResponse.of(
                "INTERNAL_SERVER_ERROR",
                "서버 내부 오류가 발생했습니다.",
                request.getRequestURI()
        );
        
        setApiErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, errorResponse);
        return "error/500";
    }
    
    private String handleApiArticleException(ArticleException e, HttpServletRequest request) {
        HttpStatus status = determineHttpStatus(e);
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                request.getRequestURI()
        );
        
        setApiErrorResponse(status, errorResponse);
        return "error/error";
    }
    
    private String handleApiCrawlerException(CrawlerException e, HttpServletRequest request) {
        HttpStatus status = determineCrawlerHttpStatus(e);
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                request.getRequestURI()
        );
        
        setApiErrorResponse(status, errorResponse);
        return "error/error";
    }
    
    private void setApiErrorResponse(HttpStatus status, ErrorResponse errorResponse) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.getResponse().setStatus(status.value());
            attributes.getResponse().setContentType("application/json;charset=UTF-8");
        }
    }
    
    private HttpStatus determineHttpStatus(ArticleException e) {
        return switch (e.getErrorCode()) {
            case "ARTICLE_NOT_FOUND", "CORPORATION_NOT_FOUND", "USER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "LIKE_ALREADY_EXISTS" -> HttpStatus.CONFLICT;
            case "VIEW_COOLDOWN_ACTIVE" -> HttpStatus.TOO_MANY_REQUESTS;
            case "INVALID_PARAMETER" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
    
    private HttpStatus determineCrawlerHttpStatus(CrawlerException e) {
        return switch (e.getErrorCode()) {
            case "CORPORATION_NOT_FOUND", "CRAWLER_NOT_FOUND", "CONTENT_ELEMENT_NOT_FOUND", "NETWORK_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "RATE_LIMIT_EXCEEDED", "BOT_DETECTION_TRIGGERED", "TOO_MANY_REQUESTS" -> HttpStatus.TOO_MANY_REQUESTS;
            case "NETWORK_FORBIDDEN", "CAPTCHA_REQUIRED", "IP_BLOCKED" -> HttpStatus.FORBIDDEN;
            case "INVALID_URL_FORMAT", "INVALID_URL_MALFORMED", "CONTENT_PARSING_FAILED", "CRAWLER_CONFIG_INVALID" -> HttpStatus.BAD_REQUEST;
            case "ARTICLE_DUPLICATE_URL", "ARTICLE_DUPLICATE_TITLE", "ARTICLE_DUPLICATE_CONTENT" -> HttpStatus.CONFLICT;
            case "CRAWLER_TIMEOUT", "NETWORK_CONNECTION_TIMEOUT", "CRAWLER_PAGE_LOAD_TIMEOUT" -> HttpStatus.REQUEST_TIMEOUT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
    
    private String getErrorTitle(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "페이지를 찾을 수 없습니다";
            case FORBIDDEN -> "접근 권한이 없습니다";
            case BAD_REQUEST -> "잘못된 요청입니다";
            case CONFLICT -> "요청이 충돌했습니다";
            case TOO_MANY_REQUESTS -> "너무 많은 요청입니다";
            case REQUEST_TIMEOUT -> "요청 시간이 초과되었습니다";
            default -> "서버 오류가 발생했습니다";
        };
    }
}