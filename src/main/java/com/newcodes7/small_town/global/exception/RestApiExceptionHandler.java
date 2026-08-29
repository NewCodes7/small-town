package com.newcodes7.small_town.global.exception;

import com.newcodes7.small_town.article.exception.ArticleException;
import com.newcodes7.small_town.article.exception.ErrorResponse;
import com.newcodes7.small_town.auth.exception.AuthException;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * REST API 전용 예외 처리 핸들러
 * API 요청(/api/*)에 대해 JSON 응답을 반환합니다.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RestApiExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFoundException(NoResourceFoundException e, WebRequest request) throws NoResourceFoundException {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();

        if (!isApiRequest(path, request)) {
            throw e; // GlobalExceptionHandler에서 처리하도록 예외를 다시 던짐
        }

        log.warn("404 Error - 리소스를 찾을 수 없음: {}", path);

        ErrorResponse errorResponse = ErrorResponse.of(
            "NOT_FOUND",
            "요청하신 리소스를 찾을 수 없습니다.",
            path
        );

        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(errorResponse);
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuthException(AuthException e, WebRequest request) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        log.warn("Auth 예외 발생 [{}]: {} at {}", e.getErrorCode(), e.getMessage(), path);

        HttpStatus status = switch (e.getErrorCode()) {
            case "AUTHENTICATION_FAILED", "UNAUTHORIZED" -> HttpStatus.UNAUTHORIZED;
            case "DUPLICATE_EMAIL" -> HttpStatus.CONFLICT;
            case "PASSWORD_MISMATCH", "INVALID_TOKEN" -> HttpStatus.BAD_REQUEST;
            case "USER_NOT_FOUND", "ROLE_NOT_FOUND", "PROVIDER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        ErrorResponse errorResponse = ErrorResponse.of(e.getErrorCode(), e.getMessage(), path);
        return ResponseEntity.status(status).body(errorResponse);
    }

    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ErrorResponse> handleSpringAuthException(AuthenticationException e, WebRequest request) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        log.warn("Spring Security 인증 실패: {} at {}", e.getMessage(), path);

        ErrorResponse errorResponse = ErrorResponse.of(
            "AUTHENTICATION_FAILED",
            "이메일 또는 비밀번호가 올바르지 않습니다.",
            path
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    /**
     * 클라이언트가 응답 도중 연결을 끊은 경우 (SSE 스트리밍 중 이탈 등).
     * 응답을 쓸 수 없는 상태이므로 아무것도 반환하지 않고 조용히 종료한다.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e, WebRequest request) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        log.debug("클라이언트 연결 종료로 응답 중단: {} at {}", e.getMessage(), path);
    }

    /**
     * 컨트롤러가 상태코드를 직접 지정해 던진 예외는 그 상태코드로 내보낸다.
     *
     * <p>이게 없으면 아래 {@code handleGeneralException(Exception)}이 먼저 잡는다 —
     * {@code ExceptionHandlerExceptionResolver}가 {@code ResponseStatusExceptionResolver}보다
     * 앞서 도는 Spring MVC 기본 순서 때문이고, 그 결과 {@code /api/*}에서 던진 429·404·503이
     * <b>전부 500 + 스택트레이스 ERROR 로그</b>가 됐다(예: /api/rag/answer 시간당 한도,
     * /api/search/articles/loadtest 동시성 상한, /api/rag/answer/loadtest의 게이트들).
     *
     * <p><b>4xx는 로그를 남기지 않는다.</b> 클라이언트 잘못이거나 의도된 거절이라 운영자가 볼 것이
     * 없고, 특히 429는 과부하 때 쏟아지므로 로깅이 그 자체로 부하가 된다
     * (load-test/results/2026-08-27-rag-ladder.md 5.3 — 붕괴 12분간 37,338줄).
     * 5xx만 남기되 스택트레이스는 붙이지 않는다 — 던진 쪽이 이미 원인을 메시지로 갖고 있다.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(
            ResponseStatusException e, WebRequest request) throws ResponseStatusException {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();

        if (!isApiRequest(path, request)) {
            throw e; // 뷰 요청은 GlobalExceptionHandler / 기본 처리로 넘긴다
        }

        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is5xxServerError()) {
            log.warn("{} 응답: {} at {}", status.value(), e.getReason(), path);
        }

        ErrorResponse errorResponse = ErrorResponse.of(
            status.name(),
            e.getReason() != null ? e.getReason() : status.getReasonPhrase(),
            path
        );

        return ResponseEntity.status(status).body(errorResponse);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception e, WebRequest request) throws Exception {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();

        // API 요청이 아니면 GlobalExceptionHandler에서 처리
        if (!isApiRequest(path, request)) {
            throw e; // GlobalExceptionHandler에서 처리하도록 예외를 다시 던짐
        }

        log.error("예상치 못한 오류 발생: {} at {}", e.getMessage(), path, e);

        ErrorResponse errorResponse = ErrorResponse.of(
            "INTERNAL_SERVER_ERROR",
            "서버 내부 오류가 발생했습니다.",
            path
        );

        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(errorResponse);
    }

    private boolean isApiRequest(String path, WebRequest request) {
        String acceptHeader = ((ServletWebRequest) request).getRequest().getHeader("Accept");
        return path.startsWith("/api/") ||
               (acceptHeader != null && acceptHeader.contains("application/json"));
    }

    @ExceptionHandler(ArticleException.class)
    public ResponseEntity<ErrorResponse> handleArticleException(ArticleException e, WebRequest request) throws ArticleException {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();

        if (!isApiRequest(path, request)) {
            throw e; // GlobalExceptionHandler에서 처리하도록 예외를 다시 던짐
        }

        log.warn("Article 예외 발생: {} at {}", e.getMessage(), path);

        HttpStatus status = determineHttpStatus(e);
        ErrorResponse errorResponse = ErrorResponse.of(
            e.getErrorCode(),
            e.getMessage(),
            path
        );

        return ResponseEntity
            .status(status)
            .body(errorResponse);
    }

    @ExceptionHandler(CrawlerException.class)
    public ResponseEntity<ErrorResponse> handleCrawlerException(CrawlerException e, WebRequest request) throws CrawlerException {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();

        if (!isApiRequest(path, request)) {
            throw e; // GlobalExceptionHandler에서 처리하도록 예외를 다시 던짐
        }

        log.warn("Crawler 예외 발생: {} at {}", e.getMessage(), path);

        HttpStatus status = determineCrawlerHttpStatus(e);
        ErrorResponse errorResponse = ErrorResponse.of(
            e.getErrorCode(),
            e.getMessage(),
            path
        );

        return ResponseEntity
            .status(status)
            .body(errorResponse);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException e, WebRequest request) throws IllegalArgumentException {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();

        if (!isApiRequest(path, request)) {
            throw e; // GlobalExceptionHandler에서 처리하도록 예외를 다시 던짐
        }

        log.warn("잘못된 인자: {} at {}", e.getMessage(), path);

        ErrorResponse errorResponse = ErrorResponse.of(
            "INVALID_ARGUMENT",
            e.getMessage(),
            path
        );

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(errorResponse);
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
}
