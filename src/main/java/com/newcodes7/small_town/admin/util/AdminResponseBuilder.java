package com.newcodes7.small_town.admin.util;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin API 응답 생성을 위한 유틸리티 클래스
 * 중복된 응답 생성 패턴을 제거하고 일관된 응답 형식을 제공
 */
public class AdminResponseBuilder {

    /**
     * 성공 응답 생성 (데이터 포함)
     * 
     * @param data 응답 데이터
     * @return 200 OK 응답
     */
    public static <T> ResponseEntity<Map<String, Object>> success(T data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * 성공 응답 생성 (데이터 없음)
     * 
     * @return 200 OK 응답
     */
    public static ResponseEntity<Map<String, Object>> success() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return ResponseEntity.ok(response);
    }

    /**
     * 성공 응답 생성 (메시지 포함)
     * 
     * @param message 성공 메시지
     * @return 200 OK 응답
     */
    public static ResponseEntity<Map<String, Object>> successWithMessage(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", message);
        return ResponseEntity.ok(response);
    }

    /**
     * 성공 응답 생성 (데이터 및 메시지 포함)
     * 
     * @param data 응답 데이터
     * @param message 성공 메시지
     * @return 200 OK 응답
     */
    public static <T> ResponseEntity<Map<String, Object>> success(T data, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        response.put("message", message);
        return ResponseEntity.ok(response);
    }

    /**
     * 에러 응답 생성 (메시지)
     * 
     * @param message 에러 메시지
     * @return 500 Internal Server Error 응답
     */
    public static ResponseEntity<Map<String, Object>> error(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.internalServerError().body(response);
    }

    /**
     * 에러 응답 생성 (예외)
     * 
     * @param e 예외
     * @return 500 Internal Server Error 응답
     */
    public static ResponseEntity<Map<String, Object>> error(Exception e) {
        return error(e.getMessage());
    }

    /**
     * 에러 응답 생성 (상태 코드 지정)
     * 
     * @param status HTTP 상태 코드
     * @param message 에러 메시지
     * @return 지정된 상태 코드의 응답
     */
    public static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Bad Request 응답 생성
     * 
     * @param message 에러 메시지
     * @return 400 Bad Request 응답
     */
    public static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return error(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Not Found 응답 생성
     * 
     * @param message 에러 메시지
     * @return 404 Not Found 응답
     */
    public static ResponseEntity<Map<String, Object>> notFound(String message) {
        return error(HttpStatus.NOT_FOUND, message);
    }
}
