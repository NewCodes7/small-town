package com.newcodes7.small_town.corporation.exception;

import com.newcodes7.small_town.global.exception.ErrorResponse;
import com.newcodes7.small_town.global.exception.InvalidParameterException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

@Slf4j
@RestControllerAdvice(basePackages = "com.newcodes7.small_town.corporation")
public class CorporationExceptionHandler {
    
    @ExceptionHandler(CorporationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCorporationNotFoundException(CorporationNotFoundException e) {
        log.warn("기업을 찾을 수 없음: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }
    
    @ExceptionHandler(DuplicateCorporationNameException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCorporationNameException(DuplicateCorporationNameException e) {
        log.warn("기업명 중복: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }
    
    @ExceptionHandler(IndustryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleIndustryNotFoundException(IndustryNotFoundException e) {
        log.warn("업종을 찾을 수 없음: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }
    
    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<ErrorResponse> handleInvalidParameterException(InvalidParameterException e) {
        log.warn("잘못된 파라미터: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }
    
    @ExceptionHandler(CorporationException.class)
    public ResponseEntity<ErrorResponse> handleCorporationException(CorporationException e) {
        log.error("기타 Corporation 예외: {}", e.getMessage(), e);
        
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