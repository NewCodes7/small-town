package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Corporation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImageMigrationService {

    private final CorporationRepository corporationRepository;
    private final S3ImageService s3ImageService;

    /**
     * S3에 저장된 모든 기업 로고 이미지를 WebP로 재압축합니다.
     *
     * @return 마이그레이션 결과 메시지
     */
    @Transactional
    public String migrateAllLogosToWebP() {
        log.info("=== 로고 이미지 WebP 마이그레이션 시작 ===");

        // logoS3Url이 있는 모든 기업 조회
        List<Corporation> corporations = corporationRepository.findAll().stream()
                .filter(corp -> corp.getLogoS3Url() != null && !corp.getLogoS3Url().trim().isEmpty())
                .filter(corp -> !corp.getLogoS3Url().endsWith(".webp")) // 이미 WebP인 경우 제외
                .toList();

        log.info("마이그레이션 대상 기업 수: {}", corporations.size());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);

        corporations.forEach(corporation -> {
            try {
                log.info("마이그레이션 시작 - 기업: {}, 원본 URL: {}",
                        corporation.getName(), corporation.getLogoS3Url());

                // S3에서 이미지 다운로드
                byte[] originalImage = s3ImageService.downloadImageFromS3(corporation.getLogoS3Url());

                // 현재 S3 키 추출하여 새로운 키 생성
                String originalS3Key = extractS3KeyFromUrl(corporation.getLogoS3Url());
                String newS3Key = changeExtensionToWebP(originalS3Key);

                // WebP로 변환 후 업로드
                String newWebPUrl = s3ImageService.uploadImageFromBytes(originalImage, newS3Key);

                // Corporation 엔티티 업데이트
                corporation.setLogoS3Url(newWebPUrl);
                corporationRepository.save(corporation);

                successCount.incrementAndGet();
                log.info("마이그레이션 성공 - 기업: {}, 새로운 URL: {}",
                        corporation.getName(), newWebPUrl);

            } catch (Exception e) {
                failCount.incrementAndGet();
                log.error("마이그레이션 실패 - 기업: {}, URL: {}",
                        corporation.getName(), corporation.getLogoS3Url(), e);
            }
        });

        String result = String.format(
                "=== 로고 이미지 WebP 마이그레이션 완료 ===\n" +
                "전체 대상: %d개\n" +
                "성공: %d개\n" +
                "실패: %d개\n" +
                "스킵: %d개",
                corporations.size(),
                successCount.get(),
                failCount.get(),
                skippedCount.get()
        );

        log.info(result);
        return result;
    }

    /**
     * 특정 기업의 로고 이미지를 WebP로 재압축합니다.
     *
     * @param corporationId 기업 ID
     * @return 마이그레이션 결과 메시지
     */
    @Transactional
    public String migrateLogoToWebP(Long corporationId) {
        log.info("=== 단일 로고 이미지 WebP 마이그레이션 시작 - 기업 ID: {} ===", corporationId);

        Corporation corporation = corporationRepository.findById(corporationId)
                .orElseThrow(() -> new IllegalArgumentException("기업을 찾을 수 없습니다: " + corporationId));

        if (corporation.getLogoS3Url() == null || corporation.getLogoS3Url().trim().isEmpty()) {
            String message = "S3 로고 URL이 없습니다: " + corporation.getName();
            log.warn(message);
            return message;
        }

        if (corporation.getLogoS3Url().endsWith(".webp")) {
            String message = "이미 WebP 형식입니다: " + corporation.getName();
            log.info(message);
            return message;
        }

        try {
            log.info("마이그레이션 시작 - 기업: {}, 원본 URL: {}",
                    corporation.getName(), corporation.getLogoS3Url());

            // S3에서 이미지 다운로드
            byte[] originalImage = s3ImageService.downloadImageFromS3(corporation.getLogoS3Url());

            // 현재 S3 키 추출하여 새로운 키 생성
            String originalS3Key = extractS3KeyFromUrl(corporation.getLogoS3Url());
            String newS3Key = changeExtensionToWebP(originalS3Key);

            // WebP로 변환 후 업로드
            String newWebPUrl = s3ImageService.uploadImageFromBytes(originalImage, newS3Key);

            // Corporation 엔티티 업데이트
            corporation.setLogoS3Url(newWebPUrl);
            corporationRepository.save(corporation);

            String result = String.format(
                    "마이그레이션 성공 - 기업: %s\n원본 URL: %s\n새로운 URL: %s",
                    corporation.getName(),
                    corporation.getLogoS3Url(),
                    newWebPUrl
            );

            log.info(result);
            return result;

        } catch (Exception e) {
            String errorMessage = String.format(
                    "마이그레이션 실패 - 기업: %s, URL: %s, 오류: %s",
                    corporation.getName(),
                    corporation.getLogoS3Url(),
                    e.getMessage()
            );
            log.error(errorMessage, e);
            throw new RuntimeException(errorMessage, e);
        }
    }

    /**
     * S3 URL에서 S3 키를 추출합니다. (S3ImageService와 동일한 로직)
     */
    private String extractS3KeyFromUrl(String s3Url) {
        // CloudFront URL 처리
        if (s3Url.contains("cloudfront.net") || s3Url.contains("cdn.")) {
            // 도메인 이후의 경로만 추출
            int pathStartIndex = s3Url.indexOf("/", 8); // "https://" 이후의 첫 번째 /
            if (pathStartIndex != -1) {
                return s3Url.substring(pathStartIndex + 1);
            }
        }

        // S3 직접 URL: https://bucket.s3.region.amazonaws.com/path/to/image.jpg
        if (s3Url.contains(".s3.") && s3Url.contains(".amazonaws.com/")) {
            int keyStartIndex = s3Url.indexOf(".amazonaws.com/") + ".amazonaws.com/".length();
            return s3Url.substring(keyStartIndex);
        }

        throw new IllegalArgumentException("유효하지 않은 S3 URL: " + s3Url);
    }

    /**
     * S3 키의 확장자를 webp로 변경합니다.
     */
    private String changeExtensionToWebP(String s3Key) {
        if (s3Key.contains(".")) {
            return s3Key.substring(0, s3Key.lastIndexOf(".")) + ".webp";
        }
        return s3Key + ".webp";
    }
}
