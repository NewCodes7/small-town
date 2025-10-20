package com.newcodes7.small_town.crawler.service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
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

    @Value("${s3.image.webp.quality:80}")
    private int webpQuality;

    /**
     * S3에서 이미지를 다운로드합니다.
     *
     * @param s3Url S3 URL (CloudFront 또는 S3 직접 URL)
     * @return 이미지 바이트 배열
     * @throws IOException 다운로드 실패 시
     */
    public byte[] downloadImageFromS3(String s3Url) throws IOException {
        try {
            // S3 키 추출
            String s3Key = extractS3KeyFromUrl(s3Url);

            // S3에서 객체 다운로드
            var s3Object = amazonS3.getObject(bucketName, s3Key);
            try (InputStream inputStream = s3Object.getObjectContent()) {
                return inputStream.readAllBytes();
            }
        } catch (Exception e) {
            log.error("S3 이미지 다운로드 실패: {}", s3Url, e);
            throw new IOException("S3 이미지 다운로드 실패: " + s3Url, e);
        }
    }

    /**
     * S3 URL에서 S3 키를 추출합니다.
     *
     * @param s3Url S3 URL
     * @return S3 키
     */
    private String extractS3KeyFromUrl(String s3Url) {
        // CloudFront URL: https://cloudfront.domain/path/to/image.jpg
        if (cloudfrontDomain != null && !cloudfrontDomain.trim().isEmpty()
                && s3Url.startsWith(cloudfrontDomain)) {
            return s3Url.substring(cloudfrontDomain.length() + 1);
        }

        // S3 직접 URL: https://bucket.s3.region.amazonaws.com/path/to/image.jpg
        if (s3Url.contains(".s3.") && s3Url.contains(".amazonaws.com/")) {
            int keyStartIndex = s3Url.indexOf(".amazonaws.com/") + ".amazonaws.com/".length();
            return s3Url.substring(keyStartIndex);
        }

        throw new IllegalArgumentException("유효하지 않은 S3 URL: " + s3Url);
    }

    /**
     * 이미지 바이트 배열을 WebP 형식으로 변환합니다.
     *
     * @param imageData 원본 이미지 바이트 배열
     * @return WebP 형식의 이미지 바이트 배열
     * @throws IOException 이미지 변환 실패 시
     */
    private byte[] convertToWebP(byte[] imageData) throws IOException {
        try {
            // scrimage로 이미지 로드
            ImmutableImage image = ImmutableImage.loader().fromBytes(imageData);

            // WebP로 변환 (quality 설정: 0-100, 기본값 80)
            WebpWriter writer = WebpWriter.DEFAULT.withQ(webpQuality);
            byte[] webpData = image.bytes(writer);

            log.debug("이미지 WebP 변환 완료 - 원본 크기: {}bytes, 변환 후: {}bytes, 압축률: {}%",
                    imageData.length,
                    webpData.length,
                    String.format("%.2f", (1 - (double)webpData.length / imageData.length) * 100));

            return webpData;
        } catch (Exception e) {
            log.warn("WebP 변환 실패, 원본 이미지 사용: {}", e.getMessage());
            // WebP 변환 실패 시 원본 반환
            return imageData;
        }
    }

    /**
     * 이미지 바이트 배열을 PNG 형식으로 변환합니다.
     *
     * @param imageData 원본 이미지 바이트 배열
     * @return PNG 형식의 이미지 바이트 배열
     * @throws IOException 이미지 변환 실패 시
     */
    private byte[] convertToPNG(byte[] imageData) throws IOException {
        try {
            // scrimage로 이미지 로드
            ImmutableImage image = ImmutableImage.loader().fromBytes(imageData);

            // PNG로 변환
            byte[] pngData = image.bytes(com.sksamuel.scrimage.nio.PngWriter.MaxCompression);

            log.debug("이미지 PNG 변환 완료 - 원본 크기: {}bytes, 변환 후: {}bytes",
                    imageData.length,
                    pngData.length);

            return pngData;
        } catch (Exception e) {
            log.warn("PNG 변환 실패, 원본 이미지 사용: {}", e.getMessage());
            // PNG 변환 실패 시 원본 반환
            return imageData;
        }
    }

    /**
     * 이미지 바이트 배열을 JPEG 형식으로 변환합니다.
     *
     * @param imageData 원본 이미지 바이트 배열
     * @return JPEG 형식의 이미지 바이트 배열
     * @throws IOException 이미지 변환 실패 시
     */
    private byte[] convertToJPEG(byte[] imageData) throws IOException {
        try {
            // scrimage로 이미지 로드
            ImmutableImage image = ImmutableImage.loader().fromBytes(imageData);

            // JPEG로 변환
            byte[] jpegData = image.bytes(com.sksamuel.scrimage.nio.JpegWriter.Default.withCompression(80));

            log.debug("이미지 JPEG 변환 완료 - 원본 크기: {}bytes, 변환 후: {}bytes",
                    imageData.length,
                    jpegData.length);

            return jpegData;
        } catch (Exception e) {
            log.warn("JPEG 변환 실패, 원본 이미지 사용: {}", e.getMessage());
            // JPEG 변환 실패 시 원본 반환
            return imageData;
        }
    }

    /**
     * 바이트 배열로부터 이미지를 S3에 업로드합니다.
     *
     * @param imageData 이미지 바이트 배열
     * @param s3Key S3 키
     * @return 업로드된 이미지 URL
     */
    public String uploadImageFromBytes(byte[] imageData, String s3Key) {
        try {
            // WebP로 변환
            byte[] processedImageData = convertToWebP(imageData);

            // S3 키를 WebP 확장자로 변경
            String webpS3Key = changeExtensionToWebP(s3Key);

            // S3에 업로드
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(processedImageData.length);
            metadata.setContentType("image/webp");
            metadata.setCacheControl("max-age=31536000"); // 1년 캐시

            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    bucketName,
                    webpS3Key,
                    new ByteArrayInputStream(processedImageData),
                    metadata
            );

            amazonS3.putObject(putObjectRequest);

            // CloudFront 사용 가능하면 CloudFront URL, 아니면 직접 S3 URL
            String resultUrl;
            if (cloudfrontDomain != null && !cloudfrontDomain.trim().isEmpty()) {
                // 운영환경: CloudFront URL 사용
                resultUrl = cloudfrontDomain + "/" + webpS3Key;
                log.info("이미지 업로드 완료 (CloudFront): {}", resultUrl);
            } else {
                // 개발환경: 직접 S3 URL 사용
                resultUrl = String.format("https://%s.s3.%s.amazonaws.com/%s",
                    bucketName, region, webpS3Key);
                log.info("이미지 업로드 완료 (직접 S3): {}", resultUrl);
            }

            return resultUrl;

        } catch (Exception e) {
            log.error("바이트 배열 이미지 업로드 실패: {}", s3Key, e);
            throw new RuntimeException("이미지 업로드 실패", e);
        }
    }

    /**
     * 바이트 배열로부터 이미지를 PNG 형식으로 S3에 업로드합니다.
     *
     * @param imageData 이미지 바이트 배열
     * @param s3Key S3 키
     * @return 업로드된 이미지 URL
     */
    public String uploadImageAsPNG(byte[] imageData, String s3Key) {
        try {
            // PNG로 변환
            byte[] processedImageData = convertToPNG(imageData);

            // S3 키를 PNG 확장자로 변경
            String pngS3Key = changeExtensionToPNG(s3Key);

            // S3에 업로드
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(processedImageData.length);
            metadata.setContentType("image/png");
            metadata.setCacheControl("max-age=31536000"); // 1년 캐시

            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    bucketName,
                    pngS3Key,
                    new ByteArrayInputStream(processedImageData),
                    metadata
            );

            amazonS3.putObject(putObjectRequest);

            // CloudFront 사용 가능하면 CloudFront URL, 아니면 직접 S3 URL
            String resultUrl;
            if (cloudfrontDomain != null && !cloudfrontDomain.trim().isEmpty()) {
                // 운영환경: CloudFront URL 사용
                resultUrl = cloudfrontDomain + "/" + pngS3Key;
                log.info("이미지 PNG 업로드 완료 (CloudFront): {}", resultUrl);
            } else {
                // 개발환경: 직접 S3 URL 사용
                resultUrl = String.format("https://%s.s3.%s.amazonaws.com/%s",
                    bucketName, region, pngS3Key);
                log.info("이미지 PNG 업로드 완료 (직접 S3): {}", resultUrl);
            }

            return resultUrl;

        } catch (Exception e) {
            log.error("바이트 배열 PNG 이미지 업로드 실패: {}", s3Key, e);
            throw new RuntimeException("PNG 이미지 업로드 실패", e);
        }
    }

    /**
     * 바이트 배열로부터 이미지를 JPEG 형식으로 S3에 업로드합니다.
     *
     * @param imageData 이미지 바이트 배열
     * @param s3Key S3 키
     * @return 업로드된 이미지 URL
     */
    public String uploadImageAsJPEG(byte[] imageData, String s3Key) {
        try {
            // JPEG로 변환
            byte[] processedImageData = convertToJPEG(imageData);

            // S3 키를 JPEG 확장자로 변경
            String jpegS3Key = changeExtensionToJPEG(s3Key);

            // S3에 업로드
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(processedImageData.length);
            metadata.setContentType("image/jpeg");
            metadata.setCacheControl("max-age=31536000"); // 1년 캐시

            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    bucketName,
                    jpegS3Key,
                    new ByteArrayInputStream(processedImageData),
                    metadata
            );

            amazonS3.putObject(putObjectRequest);

            // CloudFront 사용 가능하면 CloudFront URL, 아니면 직접 S3 URL
            String resultUrl;
            if (cloudfrontDomain != null && !cloudfrontDomain.trim().isEmpty()) {
                // 운영환경: CloudFront URL 사용
                resultUrl = cloudfrontDomain + "/" + jpegS3Key;
                log.info("이미지 JPEG 업로드 완료 (CloudFront): {}", resultUrl);
            } else {
                // 개발환경: 직접 S3 URL 사용
                resultUrl = String.format("https://%s.s3.%s.amazonaws.com/%s",
                    bucketName, region, jpegS3Key);
                log.info("이미지 JPEG 업로드 완료 (직접 S3): {}", resultUrl);
            }

            return resultUrl;

        } catch (Exception e) {
            log.error("바이트 배열 JPEG 이미지 업로드 실패: {}", s3Key, e);
            throw new RuntimeException("JPEG 이미지 업로드 실패", e);
        }
    }
    
    public String uploadImageFromUrl(String imageUrl, String corporationName) {
        try {
            // 프로토콜이 명시되지 않은 경우 https 추가
            if (imageUrl.startsWith("//")) {
                imageUrl = "https:" + imageUrl;
                log.debug("프로토콜 없는 URL에 https 추가: {}", imageUrl);
            }

            // 외부 URL에서 이미지 다운로드
            URL url = new URL(imageUrl);
            // url에 공백 포함된 경우 때문에 인코딩 처리해야 함
            URI uri = new URI(url.getProtocol(), url.getUserInfo(), url.getHost(), url.getPort(), url.getPath(), url.getQuery(), url.getRef());
            url = uri.toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000); // 10초
            connection.setReadTimeout(30000); // 30초
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");

            // 티스토리/카카오 이미지인 경우 추가 헤더 설정
            if (imageUrl.contains("daumcdn.net") || imageUrl.contains("kakaocdn.net") || imageUrl.contains("tistory.com")) {
                connection.setRequestProperty("Referer", "https://tistory.com/");
                connection.setRequestProperty("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
                connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8");
                connection.setRequestProperty("Cache-Control", "no-cache");
                log.debug("티스토리 이미지 요청에 특수 헤더 추가: {}", imageUrl);
            }
            
            byte[] imageData;
            try (InputStream inputStream = connection.getInputStream()) {
                imageData = inputStream.readAllBytes();
            }

            // PNG로 변환
            byte[] processedImageData = convertToPNG(imageData);

            // S3 키 생성 (thumbnails/corporationName/yyyy/MM/dd/uuid.png)
            String s3Key = generateS3Key(corporationName, "png");

            // S3에 업로드
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(processedImageData.length);
            metadata.setContentType("image/png");
            metadata.setCacheControl("max-age=31536000"); // 1년 캐시

            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    bucketName,
                    s3Key,
                    new ByteArrayInputStream(processedImageData),
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
            // 티스토리/카카오 이미지 403 에러는 예상되는 상황이므로 warn 레벨로 로그
            if (imageUrl.contains("daumcdn.net") || imageUrl.contains("kakaocdn.net") || imageUrl.contains("tistory.com")) {
                log.warn("티스토리 이미지 접근 제한으로 업로드 실패 (원본 URL 사용): {}", imageUrl);
            } else {
                log.error("이미지 업로드 실패: {}", imageUrl, e);
            }
            return imageUrl; // 업로드 실패 시 원본 URL 반환
        } catch (URISyntaxException e) {
            log.error("잘못된 URL 형식: {}", imageUrl, e);
            return imageUrl; // 잘못된 URL 형식 시 원본 URL 반환
        }
    }

    /**
     * S3 키의 확장자를 webp로 변경합니다.
     *
     * @param s3Key 원본 S3 키
     * @return webp 확장자로 변경된 S3 키
     */
    private String changeExtensionToWebP(String s3Key) {
        if (s3Key.contains(".")) {
            return s3Key.substring(0, s3Key.lastIndexOf(".")) + ".webp";
        }
        return s3Key + ".webp";
    }

    /**
     * S3 키의 확장자를 png로 변경합니다.
     *
     * @param s3Key 원본 S3 키
     * @return png 확장자로 변경된 S3 키
     */
    private String changeExtensionToPNG(String s3Key) {
        if (s3Key.contains(".")) {
            return s3Key.substring(0, s3Key.lastIndexOf(".")) + ".png";
        }
        return s3Key + ".png";
    }

    /**
     * S3 키의 확장자를 jpg로 변경합니다.
     *
     * @param s3Key 원본 S3 키
     * @return jpg 확장자로 변경된 S3 키
     */
    private String changeExtensionToJPEG(String s3Key) {
        if (s3Key.contains(".")) {
            return s3Key.substring(0, s3Key.lastIndexOf(".")) + ".jpg";
        }
        return s3Key + ".jpg";
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

}