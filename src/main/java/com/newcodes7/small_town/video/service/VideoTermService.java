package com.newcodes7.small_town.video.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.global.entity.VideoTerm;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;
import com.newcodes7.small_town.video.repository.VideoRepository;
import com.newcodes7.small_town.video.repository.VideoTermRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoTermService {

    private final VideoRepository videoRepository;
    private final VideoTermRepository videoTermRepository;
    private final TermRepository termRepository;
    private final MorphemeAnalyzer morphemeAnalyzer;

    /**
     * 모든 video의 term을 추출하고 저장
     * 이미 term이 있는 video는 건너뜀
     */
    @Transactional
    public VideoTermExtractionResult extractAndSaveAllVideoTerms() {
        log.info("모든 video term 추출 시작 (기존 term이 있는 video는 건너뜀)");
        long startTime = System.currentTimeMillis();

        VideoTermExtractionResult result = new VideoTermExtractionResult();
        int batchSize = 100;
        int page = 0;

        while (true) {
            Pageable pageable = PageRequest.of(page, batchSize, Sort.by("id").ascending());
            Page<Video> videos = videoRepository.findByDeletedAtIsNull(pageable);

            if (videos.isEmpty()) {
                break;
            }

            for (Video video : videos) {
                try {
                    // 이미 term이 있으면 건너뛰기
                    if (videoTermRepository.existsByVideoId(video.getId())) {
                        result.incrementSkippedVideos();

                        if ((result.getProcessedVideos() + result.getSkippedVideos()) % 100 == 0) {
                            log.info("처리 진행 중: 처리={}, 건너뜀={}, term={}",
                                    result.getProcessedVideos(),
                                    result.getSkippedVideos(),
                                    result.getTotalTerms());
                        }
                        continue;
                    }

                    int termCount = extractAndSaveTermsForVideo(video);
                    result.incrementProcessedVideos();
                    result.addTermCount(termCount);

                    if ((result.getProcessedVideos() + result.getSkippedVideos()) % 100 == 0) {
                        log.info("처리 진행 중: 처리={}, 건너뜀={}, term={}",
                                result.getProcessedVideos(),
                                result.getSkippedVideos(),
                                result.getTotalTerms());
                    }
                } catch (Exception e) {
                    log.error("Video ID {} term 추출 실패: {}", video.getId(), e.getMessage(), e);
                    result.incrementFailedVideos();
                }
            }

            page++;

            if (!videos.hasNext()) {
                break;
            }
        }

        long endTime = System.currentTimeMillis();
        result.setProcessingTimeMs(endTime - startTime);

        log.info("모든 video term 추출 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                result.getProcessedVideos(), result.getSkippedVideos(),
                result.getFailedVideos(), result.getTotalTerms(),
                result.getProcessingTimeMs());

        return result;
    }

    /**
     * 특정 video의 term을 추출하고 저장
     * Term 엔티티는 재사용하여 중복 저장하지 않음
     *
     * @param video 대상 video
     * @return 추출된 term 개수
     */
    @Transactional
    public int extractAndSaveTermsForVideo(Video video) {
        // 기존 term 삭제
        videoTermRepository.deleteByVideoId(video.getId());

        // title과 translatedTitle에서 term 추출
        List<String> texts = new ArrayList<>();
        if (video.getTitle() != null && !video.getTitle().trim().isEmpty()) {
            texts.add(video.getTitle());
        }
        if (video.getTranslatedTitle() != null && !video.getTranslatedTitle().trim().isEmpty()) {
            texts.add(video.getTranslatedTitle());
        }

        if (texts.isEmpty()) {
            log.warn("Video ID {} has no title or translatedTitle", video.getId());
            return 0;
        }

        // term 추출
        Map<String, MorphemeAnalyzer.TermInfo> termMap = morphemeAnalyzer.extractTermsFromMultipleTexts(texts);

        // term 저장
        List<VideoTerm> videoTerms = new ArrayList<>();
        for (MorphemeAnalyzer.TermInfo termInfo : termMap.values()) {
            // Term 엔티티 찾기 또는 생성
            Term term = termRepository.findByTermAndTermType(termInfo.getTerm(), termInfo.getTermType())
                    .orElseGet(() -> {
                        Term newTerm = Term.builder()
                                .term(termInfo.getTerm())
                                .termType(termInfo.getTermType())
                                .build();
                        return termRepository.save(newTerm);
                    });

            // VideoTerm 생성 (Term 엔티티 참조)
            VideoTerm videoTerm = VideoTerm.builder()
                    .video(video)
                    .term(term)
                    .frequency(termInfo.getFrequency())
                    .build();
            videoTerms.add(videoTerm);
        }

        if (!videoTerms.isEmpty()) {
            videoTermRepository.saveAll(videoTerms);
            log.debug("Video ID {} term 저장 완료: {} terms", video.getId(), videoTerms.size());
        }

        return videoTerms.size();
    }

    /**
     * 특정 video ID의 term 조회
     */
    @Transactional(readOnly = true)
    public List<VideoTerm> getVideoTerms(Long videoId) {
        return videoTermRepository.findByVideoId(videoId);
    }

    /**
     * Term 추출 결과 DTO
     */
    public static class VideoTermExtractionResult {
        private int processedVideos = 0;
        private int skippedVideos = 0;
        private int failedVideos = 0;
        private int totalTerms = 0;
        private long processingTimeMs = 0;

        public void incrementProcessedVideos() {
            this.processedVideos++;
        }

        public void incrementSkippedVideos() {
            this.skippedVideos++;
        }

        public void incrementFailedVideos() {
            this.failedVideos++;
        }

        public void addTermCount(int count) {
            this.totalTerms += count;
        }

        public int getProcessedVideos() {
            return processedVideos;
        }

        public int getSkippedVideos() {
            return skippedVideos;
        }

        public int getFailedVideos() {
            return failedVideos;
        }

        public int getTotalTerms() {
            return totalTerms;
        }

        public long getProcessingTimeMs() {
            return processingTimeMs;
        }

        public void setProcessingTimeMs(long processingTimeMs) {
            this.processingTimeMs = processingTimeMs;
        }
    }
}
