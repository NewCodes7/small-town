package com.newcodes7.small_town.corporation.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/images")
public class ImageController {
    
    private static final String LOGO_DIR = "src/main/resources/static/images/logos/";
    private static final String DEFAULT_LOGO = "static/images/default-logo.png";
    
    /**
     * 로고 이미지를 서빙합니다.
     * 파일이 없으면 기본 로고를 반환합니다.
     */
    @GetMapping("/logos/{filename}")
    public ResponseEntity<Resource> getLogoImage(@PathVariable String filename) {
        try {
            // 파일 경로 검증 (보안: path traversal 방지)
            if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                log.warn("Invalid filename attempted: {}", filename);
                return getDefaultLogo();
            }
            
            // 로고 파일 경로
            Path logoPath = Paths.get(LOGO_DIR + filename);
            
            // 파일 존재 여부 확인
            if (!Files.exists(logoPath) || !Files.isReadable(logoPath)) {
                log.debug("Logo file not found: {}", filename);
                return getDefaultLogo();
            }
            
            // 파일을 Resource로 변환
            Resource resource = new ClassPathResource("static/images/logos/" + filename);
            
            if (!resource.exists()) {
                log.debug("Logo resource not found: {}", filename);
                return getDefaultLogo();
            }
            
            // 컨텐츠 타입 결정
            String contentType = getContentType(filename);
            
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS)
                            .cachePublic())
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .body(resource);
                    
        } catch (Exception e) {
            log.error("Error serving logo image: {}", filename, e);
            return getDefaultLogo();
        }
    }
    
    /**
     * 기본 로고 이미지를 반환합니다.
     */
    private ResponseEntity<Resource> getDefaultLogo() {
        try {
            Resource defaultResource = new ClassPathResource(DEFAULT_LOGO);
            
            if (!defaultResource.exists()) {
                // 기본 로고도 없으면 404 반환
                return ResponseEntity.notFound().build();
            }
            
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS)
                            .cachePublic())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
                    .body(defaultResource);
                    
        } catch (Exception e) {
            log.error("Error serving default logo", e);
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * 파일 확장자에 따른 Content-Type 결정
     */
    private String getContentType(String filename) {
        String extension = getFileExtension(filename).toLowerCase();
        
        return switch (extension) {
            case "png" -> MediaType.IMAGE_PNG_VALUE;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG_VALUE;
            case "gif" -> MediaType.IMAGE_GIF_VALUE;
            case "svg" -> "image/svg+xml";
            case "webp" -> "image/webp";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }
    
    /**
     * 파일 확장자 추출
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }
}