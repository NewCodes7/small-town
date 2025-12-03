package com.newcodes7.small_town.article.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.article.repository.TermSynonymRepository;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.entity.TermSynonym;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * TermSynonym 서비스
 * Term 간의 유의어 관계를 관리
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class TermSynonymService {

    private final TermSynonymRepository termSynonymRepository;
    private final TermRepository termRepository;

    /**
     * 유의어 관계 추가
     * term_id < synonym_term_id 규칙 준수
     *
     * @param termId1 첫 번째 term ID
     * @param termId2 두 번째 term ID
     * @return 생성된 TermSynonym
     * @throws IllegalArgumentException term이 존재하지 않거나 이미 유의어 관계가 있는 경우
     */
    @Transactional
    public TermSynonym addSynonym(Long termId1, Long termId2) {
        // 같은 term끼리는 유의어 불가
        if (termId1.equals(termId2)) {
            throw new IllegalArgumentException("같은 term끼리는 유의어로 등록할 수 없습니다.");
        }

        // term 존재 확인
        Term term1 = termRepository.findById(termId1)
            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 term입니다. ID: " + termId1));
        Term term2 = termRepository.findById(termId2)
            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 term입니다. ID: " + termId2));

        // 이미 유의어 관계가 있는지 확인
        if (termSynonymRepository.existsByTermIds(termId1, termId2)) {
            throw new IllegalArgumentException(String.format("'%s'와 '%s'는 이미 유의어 관계입니다.",
                term1.getTerm(), term2.getTerm()));
        }

        // term_id < synonym_term_id 규칙 적용
        Term smallerTerm = termId1 < termId2 ? term1 : term2;
        Term largerTerm = termId1 < termId2 ? term2 : term1;

        TermSynonym synonym = TermSynonym.builder()
            .term(smallerTerm)
            .synonymTerm(largerTerm)
            .build();

        TermSynonym saved = termSynonymRepository.save(synonym);

        log.info("유의어 관계 추가: '{}' ↔ '{}'", smallerTerm.getTerm(), largerTerm.getTerm());

        return saved;
    }

    /**
     * term 문자열로 유의어 관계 추가
     * 존재하지 않는 term은 자동으로 생성
     *
     * @param termStr1 첫 번째 term 문자열
     * @param termStr2 두 번째 term 문자열
     * @return 생성된 TermSynonym
     */
    @Transactional
    public TermSynonym addSynonymByTermString(String termStr1, String termStr2) {
        Term term1 = termRepository.findByTermAndTermType(termStr1, "NNG")
            .orElseGet(() -> createNewTerm(termStr1));
        Term term2 = termRepository.findByTermAndTermType(termStr2, "NNG")
            .orElseGet(() -> createNewTerm(termStr2));

        return addSynonym(term1.getId(), term2.getId());
    }

    /**
     * 새로운 Term 생성
     *
     * @param termString term 문자열
     * @return 생성된 Term
     */
    private Term createNewTerm(String termString) {
        // 초성 및 자모 분리 로직은 생략 (필요 시 KoreanTextProcessor 사용)
        Term newTerm = Term.builder()
            .term(termString)
            .termType("NNG")  // 기본값: 일반 명사
            .build();

        Term saved = termRepository.save(newTerm);
        log.info("새로운 Term 생성: '{}'", termString);
        return saved;
    }

    /**
     * 유의어 관계 삭제
     *
     * @param synonymId TermSynonym ID
     */
    @Transactional
    public void deleteSynonym(Long synonymId) {
        TermSynonym synonym = termSynonymRepository.findById(synonymId)
            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유의어 관계입니다. ID: " + synonymId));

        log.info("유의어 관계 삭제: '{}' ↔ '{}'",
            synonym.getTerm().getTerm(), synonym.getSynonymTerm().getTerm());

        termSynonymRepository.delete(synonym);
    }

    /**
     * 두 term 간의 유의어 관계 삭제
     *
     * @param termId1 첫 번째 term ID
     * @param termId2 두 번째 term ID
     */
    @Transactional
    public void deleteSynonymByTermIds(Long termId1, Long termId2) {
        TermSynonym synonym = termSynonymRepository.findByTermIds(termId1, termId2)
            .orElseThrow(() -> new IllegalArgumentException(
                String.format("유의어 관계가 존재하지 않습니다. termId1: %d, termId2: %d", termId1, termId2)));

        termSynonymRepository.delete(synonym);
    }

    /**
     * 특정 term의 모든 유의어 ID 조회 (양방향)
     * 검색에 사용
     *
     * @param termId term ID
     * @return 유의어 term ID 목록 (원본 termId 포함)
     */
    public List<Long> getSynonymTermIds(Long termId) {
        List<Long> synonymIds = termSynonymRepository.findSynonymTermIdsByTermId(termId);

        // 원본 termId도 포함
        List<Long> result = new ArrayList<>();
        result.add(termId);
        result.addAll(synonymIds);

        return result;
    }

    /**
     * 특정 term의 모든 유의어 조회 (양방향)
     *
     * @param termId term ID
     * @return 유의어 Term 목록
     */
    public List<Term> getSynonymTerms(Long termId) {
        return termSynonymRepository.findSynonymTermsByTermId(termId);
    }

    /**
     * 특정 term이 포함된 모든 유의어 관계 조회
     *
     * @param termId term ID
     * @return TermSynonym 목록
     */
    public List<TermSynonym> getSynonymRelations(Long termId) {
        return termSynonymRepository.findAllByTermId(termId);
    }

    /**
     * 모든 유의어 관계 조회 (관리 UI용)
     *
     * @return TermSynonym 목록
     */
    public List<TermSynonym> getAllSynonyms() {
        return termSynonymRepository.findAllWithTerms();
    }

    /**
     * 유의어 관계 존재 여부 확인
     *
     * @param termId1 첫 번째 term ID
     * @param termId2 두 번째 term ID
     * @return 유의어 관계 존재 여부
     */
    public boolean existsSynonym(Long termId1, Long termId2) {
        return termSynonymRepository.existsByTermIds(termId1, termId2);
    }

    /**
     * 여러 term의 유의어를 포함한 전체 term ID 목록 조회
     * 검색에서 여러 term 조합 시 사용
     *
     * @param termIds 원본 term ID 목록
     * @return 유의어가 포함된 전체 term ID 목록 (중복 제거)
     */
    public List<Long> expandTermIdsWithSynonyms(List<Long> termIds) {
        return termIds.stream()
            .flatMap(termId -> getSynonymTermIds(termId).stream())
            .distinct()
            .collect(Collectors.toList());
    }
}
