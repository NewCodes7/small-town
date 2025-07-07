package com.newcodes7.small_town.corporation.service;

import com.newcodes7.small_town.service.S3ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadService {
    
    private final S3ImageService s3ImageService;
    
    @Value("${s3.upload.enabled:false}")
    private boolean s3UploadEnabled;
    
    @Value("${cloud.aws.credentials.access-key:}")
    private String awsAccessKey;
    
    private static final String UPLOAD_DIR = "src/main/resources/static/images/logos/";
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int LOGO_SIZE = 200; // 200x200 px
    
    /**
     * 회사 로고 파일을 업로드하고 리사이징합니다.
     * 
     * @param file 업로드할 이미지 파일
     * @param corporationId 회사 ID
     * @return 저장된 파일명
     * @throws IOException 파일 처리 중 오류
     */
    public String saveLogoFile(MultipartFile file, Long corporationId) throws IOException {
        // 파일 검증
        validateFile(file);
        
        // 파일명 생성
        String filename = generateFilename(file, corporationId);
        
        // 디렉토리 생성
        createDirectoryIfNotExists();
        
        // 이미지 리사이징 및 저장
        BufferedImage originalImage = ImageIO.read(file.getInputStream());
        BufferedImage resizedImage = resizeImage(originalImage, LOGO_SIZE);
        
        // 파일 저장
        File outputFile = new File(UPLOAD_DIR + filename);
        String formatName = getFileExtension(filename);
        ImageIO.write(resizedImage, formatName, outputFile);
        
        log.info("로고 파일 저장 완료: {} (회사 ID: {})", filename, corporationId);
        return filename;
    }
    
    /**
     * S3 업로드가 가능한지 확인합니다.
     */
    private boolean isS3Available() {
        return s3UploadEnabled && awsAccessKey != null && !awsAccessKey.trim().isEmpty();
    }
    
    /**
     * 회사 로고 파일을 S3에 업로드합니다.
     * 
     * @param file 업로드할 이미지 파일
     * @param corporationName 회사 이름
     * @return S3 URL
     * @throws IOException 파일 처리 중 오류
     */
    public String saveLogoFileToS3(MultipartFile file, String corporationName) throws IOException {
        if (!isS3Available()) {
            throw new IOException("S3 업로드가 비활성화되었거나 AWS 자격증명이 설정되지 않았습니다.");
        }
        // 파일 검증
        validateFile(file);
        
        // 이미지 리사이징
        BufferedImage originalImage = ImageIO.read(file.getInputStream());
        BufferedImage resizedImage = resizeImage(originalImage, LOGO_SIZE);
        
        // 리사이징된 이미지를 바이트 배열로 변환
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String formatName = getFileExtension(file.getOriginalFilename());
        ImageIO.write(resizedImage, formatName, baos);
        
        // 임시 URL 생성하여 S3에 업로드
        String tempUrl = "data:image/" + formatName + ";base64," + 
                        java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
        
        // S3ImageService의 uploadLogoFromBytes 메서드 사용
        return uploadLogoFromBytes(baos.toByteArray(), corporationName, formatName);
    }
    
    /**
     * 바이트 배열을 S3에 로고로 업로드합니다.
     */
    private String uploadLogoFromBytes(byte[] imageData, String corporationName, String fileExtension) {
        try {
            // S3 키 생성 (logos/corporationName/yyyy/MM/dd/uuid.extension)
            String s3Key = generateS3LogoKey(corporationName, fileExtension);
            
            // S3ImageService를 사용하여 업로드
            return s3ImageService.uploadImageFromBytes(imageData, s3Key);
        } catch (Exception e) {
            log.error("S3 로고 업로드 실패: {}", corporationName, e);
            throw new RuntimeException("S3 로고 업로드 실패", e);
        }
    }
    
    /**
     * S3 로고 키 생성
     */
    private String generateS3LogoKey(String corporationName, String fileExtension) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String datePrefix = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String uuid = UUID.randomUUID().toString();
        
        return String.format("logos/%s/%s/%s.%s", 
                corporationName.toLowerCase().replaceAll("\\s+", "-"), 
                datePrefix, 
                uuid, 
                fileExtension);
    }
    
    /**
     * 기존 로고 파일을 삭제합니다.
     * 
     * @param filename 삭제할 파일명
     */
    public void deleteLogoFile(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return;
        }
        
        try {
            Path filePath = Paths.get(UPLOAD_DIR + filename);
            Files.deleteIfExists(filePath);
            log.info("로고 파일 삭제 완료: {}", filename);
        } catch (IOException e) {
            log.error("로고 파일 삭제 실패: {}", filename, e);
        }
    }
    
    /**
     * 파일 검증
     */
    private void validateFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IOException("파일이 비어있습니다.");
        }
        
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IOException("파일 크기가 너무 큽니다. (최대 5MB)");
        }
        
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IOException("이미지 파일만 업로드 가능합니다.");
        }
        
        // 지원하는 형식 확인
        String[] supportedTypes = {"image/jpeg", "image/jpg", "image/png", "image/gif"};
        boolean isSupported = false;
        for (String type : supportedTypes) {
            if (type.equals(contentType)) {
                isSupported = true;
                break;
            }
        }
        
        if (!isSupported) {
            throw new IOException("지원하지 않는 이미지 형식입니다. (JPG, PNG, GIF만 지원)");
        }
    }
    
    /**
     * 고유한 파일명 생성
     */
    private String generateFilename(MultipartFile file, Long corporationId) {
        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        
        // corporationId_UUID.extension 형식
        return String.format("corporation_%d_%s.%s", 
            corporationId, 
            UUID.randomUUID().toString().substring(0, 8), 
            extension);
    }
    
    /**
     * 파일 확장자 추출
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "png"; // 기본값
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
    
    /**
     * 디렉토리 생성
     */
    private void createDirectoryIfNotExists() throws IOException {
        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
    }
    
    /**
     * 이미지 리사이징 (정사각형으로)
     */
    private BufferedImage resizeImage(BufferedImage originalImage, int size) {
        // 원본 이미지 크기
        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();
        
        // 정사각형 배경 이미지 생성 (흰색)
        BufferedImage resizedImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resizedImage.createGraphics();
        
        // 렌더링 품질 향상
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // 흰색 배경
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);
        
        // 이미지 비율에 맞춰 크기 조정
        double scale = Math.min((double) size / originalWidth, (double) size / originalHeight);
        int scaledWidth = (int) (originalWidth * scale);
        int scaledHeight = (int) (originalHeight * scale);
        
        // 중앙 배치
        int x = (size - scaledWidth) / 2;
        int y = (size - scaledHeight) / 2;
        
        g.drawImage(originalImage, x, y, scaledWidth, scaledHeight, null);
        g.dispose();
        
        return resizedImage;
    }
}