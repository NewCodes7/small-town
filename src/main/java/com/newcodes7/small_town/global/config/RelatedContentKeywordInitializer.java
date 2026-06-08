package com.newcodes7.small_town.global.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.newcodes7.small_town.term.repository.RelatedContentKeywordRepository;
import com.newcodes7.small_town.global.entity.RelatedContentKeyword;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관련 글 키워드 초기 데이터 삽입
 * 애플리케이션 시작 시 DB에 기본 키워드들을 자동으로 등록합니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RelatedContentKeywordInitializer implements ApplicationRunner {

    private final RelatedContentKeywordRepository repository;

    // 기본 키워드 목록
    private static final List<String> DEFAULT_KEYWORDS = Arrays.asList(
        "추천 콘텐츠",
        "관련 게시글",
        "추천 글",
        "관련 글",
        "다른 글 보기",
        "이전 글",
        "다음 글",
        "연관 게시글",
        "연관 글",
        "related posts",
        "recommended",
        "you may also like",
        "similar posts",
        "more posts",
        "read more",
        "other articles"
    );

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 이미 키워드가 있으면 초기화하지 않음
        if (repository.count() > 0) {
            log.info("관련 글 키워드가 이미 존재합니다. 초기화를 건너뜁니다. (현재 {}개)", repository.count());
            return;
        }

        log.info("관련 글 키워드 초기 데이터 삽입 시작...");

        int inserted = 0;
        for (String keyword : DEFAULT_KEYWORDS) {
            try {
                if (!repository.existsByKeyword(keyword)) {
                    RelatedContentKeyword newKeyword = RelatedContentKeyword.builder()
                        .keyword(keyword)
                        .build();
                    repository.save(newKeyword);
                    inserted++;
                    log.debug("키워드 등록: {}", keyword);
                }
            } catch (Exception e) {
                log.warn("키워드 '{}' 등록 실패: {}", keyword, e.getMessage());
            }
        }

        log.info("관련 글 키워드 초기 데이터 삽입 완료: {}개 등록됨", inserted);
    }
}
