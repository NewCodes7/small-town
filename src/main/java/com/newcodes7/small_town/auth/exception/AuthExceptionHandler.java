package com.newcodes7.small_town.auth.exception;

import com.newcodes7.small_town.global.exception.ErrorResponse;
import com.newcodes7.small_town.global.exception.UserNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

@Slf4j
@RestControllerAdvice(basePackages = "com.newcodes7.small_town.auth")
public class AuthExceptionHandler {
    
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmailException(DuplicateEmailException e) {
        log.warn("이메일 중복: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }
    
    @ExceptionHandler(PasswordMismatchException.class)
    public ResponseEntity<ErrorResponse> handlePasswordMismatchException(PasswordMismatchException e) {
        log.warn("비밀번호 불일치: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }
    
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFoundException(UserNotFoundException e) {
        log.warn("사용자를 찾을 수 없음: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }
    
    @ExceptionHandler(RoleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoleNotFoundException(RoleNotFoundException e) {
        log.error("역할을 찾을 수 없음: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
    
    @ExceptionHandler(ProviderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProviderNotFoundException(ProviderNotFoundException e) {
        log.error("인증 제공자를 찾을 수 없음: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
    
    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTokenException(InvalidTokenException e) {
        log.warn("유효하지 않은 토큰: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }
    
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationFailedException(AuthenticationFailedException e) {
        log.warn("인증 실패: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }
    
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedException(UnauthorizedException e) {
        log.warn("인증되지 않은 접근: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }
    
    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ErrorResponse> handleSpringAuthenticationException(AuthenticationException e) {
        log.warn("Spring Security 인증 실패: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                "AUTHENTICATION_FAILED",
                "이메일 또는 비밀번호가 올바르지 않습니다.",
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }
    
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuthException(AuthException e) {
        log.error("기타 Auth 예외: {}", e.getMessage(), e);
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("예상치 못한 예외 발생: {}", e.getMessage(), e);
        
        ErrorResponse errorResponse = ErrorResponse.of(
                "INTERNAL_SERVER_ERROR",
                "서버 내부 오류가 발생했습니다.",
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
    
    private String getCurrentPath() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRequestURI();
        }
        return "unknown";
    }
}