package com.newcodes7.small_town.global.config;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.ko.KoreanPartOfSpeechStopFilter;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.newcodes7.small_town.article.repository.UserDictionaryRepository;
import com.newcodes7.small_town.global.entity.UserDictionary;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class NoriConfig {

    private final UserDictionaryRepository userDictionaryRepository;

    /**
     * Nori KoreanAnalyzer Bean 등록
     * MeCab-Ko 사전 기반으로 Komoran과 동일한 세종 품사 태그셋 사용
     *
     * 사용자 정의 사전을 DB에서 로드하여 적용
     * DecompoundMode.MIXED: 복합어 원형 + 분리 토큰 모두 출력
     */
    @Bean
    public KoreanAnalyzer koreanAnalyzer() {
        log.info("Nori KoreanAnalyzer 초기화 시작");
        long startTime = System.currentTimeMillis();

        org.apache.lucene.analysis.ko.dict.UserDictionary userDict = loadUserDictionary();

        KoreanAnalyzer analyzer = new KoreanAnalyzer(
                userDict,
                KoreanTokenizer.DecompoundMode.MIXED,
                KoreanPartOfSpeechStopFilter.DEFAULT_STOP_TAGS,
                false
        );

        long endTime = System.currentTimeMillis();
        log.info("Nori KoreanAnalyzer 초기화 완료 (소요 시간: {}ms)", endTime - startTime);

        return analyzer;
    }

    /**
     * DB에서 사용자 정의 사전을 로드하여 Nori UserDictionary로 변환
     * Nori 포맷: 한 줄에 단어 하나 (POS는 자동 결정)
     * 공백 포함 단어는 Nori가 세그멘테이션으로 해석하므로 제외
     */
    private org.apache.lucene.analysis.ko.dict.UserDictionary loadUserDictionary() {
        try {
            List<UserDictionary> userDictionaries = userDictionaryRepository.findAllOrderByCreatedAtDesc();

            if (userDictionaries.isEmpty()) {
                log.info("사용자 정의 사전이 비어있습니다.");
                return null;
            }

            StringBuilder sb = new StringBuilder();
            int addedCount = 0;
            int skippedCount = 0;

            for (UserDictionary dict : userDictionaries) {
                String word = dict.getWord().trim();
                if (word.isEmpty() || word.contains(" ")) {
                    skippedCount++;
                    log.debug("사용자 사전 항목 건너뜀 (공백 포함): {}", dict.getWord());
                    continue;
                }
                sb.append(word).append("\n");
                addedCount++;
            }

            if (addedCount == 0) {
                log.info("유효한 사용자 정의 사전 항목이 없습니다. (건너뜀: {} 개)", skippedCount);
                return null;
            }

            org.apache.lucene.analysis.ko.dict.UserDictionary noriUserDict =
                    org.apache.lucene.analysis.ko.dict.UserDictionary.open(new StringReader(sb.toString()));

            log.info("사용자 정의 사전 적용 완료: {} 개의 단어 (건너뜀: {} 개)", addedCount, skippedCount);
            return noriUserDict;
        } catch (IOException e) {
            log.error("사용자 사전 적용 실패: {}", e.getMessage(), e);
            return null;
        }
    }
}
