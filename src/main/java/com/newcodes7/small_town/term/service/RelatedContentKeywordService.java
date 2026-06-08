package com.newcodes7.small_town.term.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.term.repository.RelatedContentKeywordRepository;
import com.newcodes7.small_town.global.entity.RelatedContentKeyword;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RelatedContentKeyword 관리 서비스
 * 본문 추출 시 제거할 관련 글/추천 글 키워드 CRUD 관리
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RelatedContentKeywordService {

    private final RelatedContentKeywordRepository repository;

    /**
     * 모든 키워드 조회
     */
    public List<RelatedContentKeyword> getAllKeywords() {
        return repository.findAllByOrderByCreatedAtAsc();
    }

    /**
     * 모든 키워드를 문자열 리스트로 조회 (ContentExtractor에서 사용)
     */
    public List<String> getAllKeywordStrings() {
        return repository.findAllByOrderByCreatedAtAsc()
            .stream()
            .map(RelatedContentKeyword::getKeyword)
            .collect(Collectors.toList());
    }

    /**
     * 키워드 추가
     */
    @Transactional
    public RelatedContentKeyword addKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("키워드는 비어있을 수 없습니다.");
        }

        String trimmedKeyword = keyword.trim();

        if (repository.existsByKeyword(trimmedKeyword)) {
            throw new IllegalArgumentException("이미 존재하는 키워드입니다: " + trimmedKeyword);
        }

        RelatedContentKeyword newKeyword = RelatedContentKeyword.builder()
            .keyword(trimmedKeyword)
            .build();

        RelatedContentKeyword saved = repository.save(newKeyword);
        log.info("관련 글 키워드 추가: {}", trimmedKeyword);
        return saved;
    }

    /**
     * 키워드 삭제
     */
    @Transactional
    public void deleteKeyword(Long id) {
        RelatedContentKeyword keyword = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("키워드를 찾을 수 없습니다: " + id));

        repository.delete(keyword);
        log.info("관련 글 키워드 삭제: {}", keyword.getKeyword());
    }

    /**
     * 키워드 수정
     */
    @Transactional
    public RelatedContentKeyword updateKeyword(Long id, String newKeyword) {
        if (newKeyword == null || newKeyword.trim().isEmpty()) {
            throw new IllegalArgumentException("키워드는 비어있을 수 없습니다.");
        }

        RelatedContentKeyword keyword = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("키워드를 찾을 수 없습니다: " + id));

        String trimmedNewKeyword = newKeyword.trim();

        // 다른 키워드와 중복 체크 (자기 자신 제외)
        if (!keyword.getKeyword().equals(trimmedNewKeyword) && repository.existsByKeyword(trimmedNewKeyword)) {
            throw new IllegalArgumentException("이미 존재하는 키워드입니다: " + trimmedNewKeyword);
        }

        keyword.setKeyword(trimmedNewKeyword);
        RelatedContentKeyword updated = repository.save(keyword);
        log.info("관련 글 키워드 수정: {} -> {}", keyword.getKeyword(), trimmedNewKeyword);
        return updated;
    }
}
