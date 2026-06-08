package com.newcodes7.small_town.term.service;

import com.newcodes7.small_town.term.dto.StackExchangeTagDto;
import com.newcodes7.small_town.term.repository.TermRepository;
import com.newcodes7.small_town.term.repository.UserDictionaryRepository;
import com.newcodes7.small_town.crawler.integration.translation.TranslationService;
import com.newcodes7.small_town.term.service.TermSynonymService;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.entity.UserDictionary;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 기술 용어 사전 관리 서비스
 * StackOverflow 태그 → DeepL 번역 → UserDictionary + TermSynonym
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TechTermService {

    private final StackExchangeApiService stackExchangeApiService;
    private final TranslationService translationService;
    private final UserDictionaryRepository userDictionaryRepository;
    private final TermRepository termRepository;
    private final TermSynonymService termSynonymService;

    /**
     * StackOverflow 태그 수집 → DeepL 번역 → 사전 저장 → Synonym 등록
     * 각 단계는 독립적인 트랜잭션으로 실행되어 부분 실패를 허용
     *
     * @param limit 가져올 태그 개수
     * @param minCount 최소 사용 횟수
     * @return 처리 결과
     */
    public TechTermCollectionResult collectAndProcessTechTerms(int limit, int minCount) {
        return collectAndProcessTechTerms(limit, minCount, 0);
    }

    /**
     * StackOverflow 태그 수집 → DeepL 번역 → 사전 저장 → Synonym 등록 (offset 지원)
     * 각 단계는 독립적인 트랜잭션으로 실행되어 부분 실패를 허용
     *
     * @param limit 가져올 태그 개수
     * @param minCount 최소 사용 횟수
     * @param offset 건너뛸 태그 개수
     * @return 처리 결과
     */
    public TechTermCollectionResult collectAndProcessTechTerms(int limit, int minCount, int offset) {
        long startTime = System.currentTimeMillis();

        TechTermCollectionResult result = new TechTermCollectionResult();
        result.setRequestedLimit(limit);
        result.setMinCount(minCount);
        result.setOffset(offset);

        try {
            // Step 1: StackOverflow 태그 가져오기 (offset 적용)
            log.info("Step 1: Fetching StackOverflow tags (limit: {}, minCount: {}, offset: {})",
                    limit, minCount, offset);
            List<StackExchangeTagDto> tags = stackExchangeApiService.fetchPopularTags(limit, minCount, offset);
            result.setFetchedTagsCount(tags.size());

            if (tags.isEmpty()) {
                log.warn("No tags fetched from StackOverflow");
                result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
                return result;
            }

            log.info("Fetched {} tags from StackOverflow", tags.size());

            // Step 2: 기존 synonym 확인 후 필요한 경우에만 DeepL 번역
            log.info("Step 2: Checking existing synonyms and translating new terms via DeepL");
            List<TechTermPair> termPairs = new ArrayList<>();

            for (StackExchangeTagDto tag : tags) {
                String englishTerm = stackExchangeApiService.convertTagToSearchTerm(tag.getName());
                String koreanTerm = null;

                try {
                    // 1. 이미 등록된 term인지 확인
                    Term existingEnglishTerm = termRepository.findByTermAndTermType(englishTerm, "NNG")
                            .orElse(null);

                    if (existingEnglishTerm != null) {
                        // 2. 기존 synonym 중 한국어 term 찾기
                        List<Term> synonyms = termSynonymService.getSynonymTerms(existingEnglishTerm.getId());

                        for (Term synonym : synonyms) {
                            // 한국어 포함 여부 확인
                            if (synonym.getTerm().matches(".*[ㄱ-ㅎㅏ-ㅣ가-힣]+.*")) {
                                koreanTerm = synonym.getTerm();
                                log.info("Using existing synonym: {} → {} (skipped DeepL)", englishTerm, koreanTerm);
                                break;
                            }
                        }
                    }

                    // 3. 기존 synonym이 없으면 DeepL로 번역
                    if (koreanTerm == null) {
                        koreanTerm = translationService.translateTitle(englishTerm);

                        if (koreanTerm != null && !koreanTerm.equals(englishTerm)) {
                            log.info("Translated via DeepL: {} → {}", englishTerm, koreanTerm);
                        } else {
                            log.debug("No Korean translation found for: {}", englishTerm);
                            koreanTerm = null;
                        }
                    }

                    // 4. 한국어 term이 있으면 termPairs에 추가
                    if (koreanTerm != null) {
                        termPairs.add(TechTermPair.builder()
                                .originalTag(tag.getName())
                                .englishTerm(englishTerm)
                                .koreanTerm(koreanTerm)
                                .usageCount(tag.getCount())
                                .build());
                    }

                } catch (Exception e) {
                    log.warn("Processing failed for '{}': {}", englishTerm, e.getMessage());
                }

                // Rate limiting (기존 synonym 사용 시에도 적용)
                Thread.sleep(500);
            }

            result.setTranslatedCount(termPairs.size());
            log.info("Translated {}/{} terms", termPairs.size(), tags.size());

            // Step 3: UserDictionary에 저장 (각각 독립 트랜잭션)
            log.info("Step 3: Saving to UserDictionary");
            int savedDictionaryCount = saveToUserDictionary(termPairs);
            result.setSavedDictionaryCount(savedDictionaryCount);
            log.info("Saved {} entries to UserDictionary", savedDictionaryCount);

            // Step 4: TermSynonym 등록 (각각 독립 트랜잭션)
            log.info("Step 4: Registering synonyms");
            int savedSynonymCount = saveTermSynonyms(termPairs);
            result.setSavedSynonymCount(savedSynonymCount);
            result.setTermPairs(termPairs);

            log.info("Created {} synonym pairs", savedSynonymCount);

            result.setSuccess(true);
            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);

            log.info("Tech term collection completed successfully in {}ms", result.getProcessingTimeMs());
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted during tech term collection", e);
            result.setSuccess(false);
            result.setErrorMessage("Process interrupted: " + e.getMessage());
            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            log.error("Error during tech term collection: {}", e.getMessage(), e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            return result;
        }
    }

    /**
     * UserDictionary에 용어 저장
     * 각 저장 작업은 독립적인 트랜잭션으로 실행
     *
     * @param termPairs 저장할 term 쌍 목록
     * @return 저장된 항목 수
     */
    @Transactional
    public int saveToUserDictionary(List<TechTermPair> termPairs) {
        int savedCount = 0;

        for (TechTermPair pair : termPairs) {
            try {
                // 영어 용어 저장
                if (!userDictionaryRepository.existsByWord(pair.getEnglishTerm())) {
                    UserDictionary englishDict = UserDictionary.builder()
                            .word(pair.getEnglishTerm())
                            .posTag("NNG")
                            .reason("StackOverflow tag: " + pair.getOriginalTag())
                            .build();
                    userDictionaryRepository.save(englishDict);
                    savedCount++;
                }

                // 한국어 용어 저장
                if (!userDictionaryRepository.existsByWord(pair.getKoreanTerm())) {
                    UserDictionary koreanDict = UserDictionary.builder()
                            .word(pair.getKoreanTerm())
                            .posTag("NNG")
                            .reason("DeepL translation of: " + pair.getEnglishTerm())
                            .build();
                    userDictionaryRepository.save(koreanDict);
                    savedCount++;
                }
            } catch (Exception e) {
                log.warn("Failed to save to UserDictionary: {} ↔ {}: {}",
                        pair.getEnglishTerm(), pair.getKoreanTerm(), e.getMessage());
            }
        }

        return savedCount;
    }

    /**
     * TermSynonym 등록
     * 각 synonym 추가는 독립적인 트랜잭션으로 실행
     *
     * @param termPairs 등록할 term 쌍 목록
     * @return 저장된 synonym 수
     */
    public int saveTermSynonyms(List<TechTermPair> termPairs) {
        int savedCount = 0;

        for (TechTermPair pair : termPairs) {
            try {
                // 각 synonym을 별도 트랜잭션으로 저장
                boolean saved = saveSingleSynonym(pair.getKoreanTerm(), pair.getEnglishTerm());
                if (saved) {
                    savedCount++;
                }
            } catch (Exception e) {
                log.warn("Failed to create synonym for {} ↔ {}: {}",
                        pair.getKoreanTerm(), pair.getEnglishTerm(), e.getMessage());
            }
        }

        return savedCount;
    }

    /**
     * 단일 TermSynonym 저장 (독립 트랜잭션)
     *
     * @param koreanTerm 한국어 term
     * @param englishTerm 영어 term
     * @return 저장 성공 여부
     */
    @Transactional
    public boolean saveSingleSynonym(String koreanTerm, String englishTerm) {
        try {
            // term 조회
            Term koreanTermEntity = termRepository.findByTermAndTermType(koreanTerm, "NNG")
                    .orElse(null);
            Term englishTermEntity = termRepository.findByTermAndTermType(englishTerm, "NNG")
                    .orElse(null);

            // 둘 다 존재하는 경우에만 중복 확인
            if (koreanTermEntity != null && englishTermEntity != null) {
                if (termSynonymService.existsSynonym(koreanTermEntity.getId(), englishTermEntity.getId())) {
                    log.debug("Synonym already exists: {} ↔ {}", koreanTerm, englishTerm);
                    return false;
                }
            }

            // synonym 추가 (term이 없으면 자동 생성됨)
            termSynonymService.addSynonymByTermString(koreanTerm, englishTerm);
            log.debug("Created synonym: {} ↔ {}", koreanTerm, englishTerm);
            return true;

        } catch (Exception e) {
            log.warn("Failed to save synonym {} ↔ {}: {}", koreanTerm, englishTerm, e.getMessage());
            return false;
        }
    }

    /**
     * 기술 용어 쌍 (영어 ↔ 한국어)
     */
    @Data
    @Builder
    public static class TechTermPair {
        private String originalTag;      // StackOverflow 태그 (machine-learning)
        private String englishTerm;      // 영어 용어 (machine learning)
        private String koreanTerm;       // 한국어 용어 (기계 학습)
        private Integer usageCount;      // StackOverflow 사용 횟수
    }

    /**
     * 기술 용어 수집 결과
     */
    @Data
    public static class TechTermCollectionResult {
        private boolean success;
        private String errorMessage;

        private int requestedLimit;
        private int minCount;
        private int offset;
        private int fetchedTagsCount;
        private int translatedCount;
        private int savedDictionaryCount;
        private int savedSynonymCount;

        private long processingTimeMs;
        private List<TechTermPair> termPairs;
    }
}
