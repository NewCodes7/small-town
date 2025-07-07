package com.newcodes7.small_town.service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.HttpMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3ImageService {

    private final AmazonS3 amazonS3;

    @Value("${s3.bucket.name}")
    private String bucketName;
    
    @Value("${cloudfront.domain:}")
    private String cloudfrontDomain;
    
    @Value("${cloud.aws.region.static}")
    private String region;

    /**
     * 바이트 배열로부터 이미지를 S3에 업로드합니다.
     * 
     * @param imageData 이미지 바이트 배열
     * @param s3Key S3 키
     * @return 업로드된 이미지 URL
     */
    public String uploadImageFromBytes(byte[] imageData, String s3Key) {
        try {
            // S3에 업로드
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(imageData.length);
            metadata.setContentType(getContentTypeFromKey(s3Key));
            metadata.setCacheControl("max-age=31536000"); // 1년 캐시

            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    bucketName,
                    s3Key,
                    new ByteArrayInputStream(imageData),
                    metadata
            );

            amazonS3.putObject(putObjectRequest);

            // CloudFront 사용 가능하면 CloudFront URL, 아니면 직접 S3 URL
            String resultUrl;
            if (cloudfrontDomain != null && !cloudfrontDomain.trim().isEmpty()) {
                // 운영환경: CloudFront URL 사용
                resultUrl = cloudfrontDomain + "/" + s3Key;
                log.info("이미지 업로드 완료 (CloudFront): {}", resultUrl);
            } else {
                // 개발환경: 직접 S3 URL 사용
                resultUrl = String.format("https://%s.s3.%s.amazonaws.com/%s", 
                    bucketName, region, s3Key);
                log.info("이미지 업로드 완료 (직접 S3): {}", resultUrl);
            }
            
            return resultUrl;

        } catch (Exception e) {
            log.error("바이트 배열 이미지 업로드 실패: {}", s3Key, e);
            throw new RuntimeException("이미지 업로드 실패", e);
        }
    }
    
    public String uploadImageFromUrl(String imageUrl, String corporationName) {
        try {
            // 외부 URL에서 이미지 다운로드
            URL url = new URL(imageUrl);
            URLConnection connection = url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            
            byte[] imageData;
            try (InputStream inputStream = connection.getInputStream()) {
                imageData = inputStream.readAllBytes();
            }

            // 파일 확장자 추출
            String fileExtension = getFileExtension(imageUrl);
            if (fileExtension.isEmpty()) {
                fileExtension = "jpg"; // 기본 확장자
            }

            // S3 키 생성 (thumbnails/corporationName/yyyy/MM/dd/uuid.extension)
            String s3Key = generateS3Key(corporationName, fileExtension);

            // S3에 업로드
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(imageData.length);
            metadata.setContentType(getContentType(fileExtension));
            metadata.setCacheControl("max-age=31536000"); // 1년 캐시

            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    bucketName,
                    s3Key,
                    new ByteArrayInputStream(imageData),
                    metadata
            );

            amazonS3.putObject(putObjectRequest);

            // CloudFront 사용 가능하면 CloudFront URL, 아니면 직접 S3 URL
            String resultUrl;
            if (cloudfrontDomain != null && !cloudfrontDomain.trim().isEmpty()) {
                // 운영환경: CloudFront URL 사용
                resultUrl = cloudfrontDomain + "/" + s3Key;
                log.info("이미지 업로드 완료 (CloudFront): {} -> {}", imageUrl, resultUrl);
            } else {
                // 개발환경: 직접 S3 URL 사용
                resultUrl = String.format("https://%s.s3.%s.amazonaws.com/%s", 
                    bucketName, region, s3Key);
                log.info("이미지 업로드 완료 (직접 S3): {} -> {}", imageUrl, resultUrl);
            }
            
            return resultUrl;

        } catch (IOException e) {
            log.error("이미지 업로드 실패: {}", imageUrl, e);
            return imageUrl; // 업로드 실패 시 원본 URL 반환
        }
    }

    private String generateS3Key(String corporationName, String fileExtension) {
        LocalDateTime now = LocalDateTime.now();
        String datePrefix = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String uuid = UUID.randomUUID().toString();
        
        return String.format("thumbnails/%s/%s/%s.%s", 
                corporationName.toLowerCase().replaceAll("\\s+", "-"), 
                datePrefix, 
                uuid, 
                fileExtension);
    }

    private String getFileExtension(String imageUrl) {
        // URL에서 확장자 추출
        String[] parts = imageUrl.split("\\.");
        if (parts.length > 1) {
            String extension = parts[parts.length - 1].toLowerCase();
            // 쿼리 파라미터 제거
            extension = extension.split("\\?")[0];
            // 유효한 이미지 확장자만 허용
            if (extension.matches("^(jpg|jpeg|png|gif|webp)$")) {
                return extension;
            }
        }
        return "";
    }

    private String getContentType(String fileExtension) {
        switch (fileExtension.toLowerCase()) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            default:
                return "image/jpeg";
        }
    }
    
    /**
     * S3 키로부터 Content-Type을 추출합니다.
     */
    private String getContentTypeFromKey(String s3Key) {
        String extension = "";
        if (s3Key.contains(".")) {
            extension = s3Key.substring(s3Key.lastIndexOf(".") + 1);
        }
        return getContentType(extension);
    }
}