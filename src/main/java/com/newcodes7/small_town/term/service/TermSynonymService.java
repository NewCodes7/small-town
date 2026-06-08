package com.newcodes7.small_town.term.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.term.repository.TermRepository;
import com.newcodes7.small_town.term.repository.TermSynonymRepository;
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

        // 유의어 관계가 생성되면 두 term 모두 hasTranslation=true로 설정
        term1.updateHasTranslation(true);
        term2.updateHasTranslation(true);
        termRepository.save(term1);
        termRepository.save(term2);

        log.info("유의어 관계 추가: '{}' ↔ '{}' (hasTranslation=true 설정)", smallerTerm.getTerm(), largerTerm.getTerm());

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
        Term term1 = termRepository.findByTermAndTermType(termStr1.toLowerCase(), "NNG")
            .orElseGet(() -> createNewTerm(termStr1));
        Term term2 = termRepository.findByTermAndTermType(termStr2.toLowerCase(), "NNG")
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
        String normalized = termString.toLowerCase();
        Term newTerm = Term.builder()
            .term(normalized)
            .termType("NNG")  // 기본값: 일반 명사
            .build();

        Term saved = termRepository.save(newTerm);
        log.info("새로운 Term 생성: '{}'", termString);
        return saved;
    }

    /**
     * 유의어 관계 수정
     * 기존 유의어 관계를 삭제하고 새로운 관계를 생성
     *
     * @param synonymId 수정할 TermSynonym ID
     * @param newTermId1 새로운 첫 번째 term ID
     * @param newTermId2 새로운 두 번째 term ID
     * @return 수정된 TermSynonym
     * @throws IllegalArgumentException 유의어가 존재하지 않거나 새로운 관계가 이미 존재하는 경우
     */
    @Transactional
    public TermSynonym updateSynonym(Long synonymId, Long newTermId1, Long newTermId2) {
        // 기존 유의어 관계 확인
        TermSynonym existingSynonym = termSynonymRepository.findById(synonymId)
            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유의어 관계입니다. ID: " + synonymId));

        // 기존 term 정보
        Long oldTermId1 = existingSynonym.getTerm().getId();
        Long oldTermId2 = existingSynonym.getSynonymTerm().getId();
        String oldTerm1Name = existingSynonym.getTerm().getTerm();
        String oldTerm2Name = existingSynonym.getSynonymTerm().getTerm();

        log.info("유의어 수정 요청 - synonymId: {}, 기존: [{}({}), {}({})] -> 새로운: [{}, {}]",
            synonymId, oldTerm1Name, oldTermId1, oldTerm2Name, oldTermId2, newTermId1, newTermId2);

        // 기존과 같은 관계면 그대로 반환
        if ((oldTermId1.equals(newTermId1) && oldTermId2.equals(newTermId2)) ||
            (oldTermId1.equals(newTermId2) && oldTermId2.equals(newTermId1))) {
            log.info("유의어 수정 건너뜀: 기존과 동일한 관계 - synonymId: {}", synonymId);
            return existingSynonym;
        }

        log.info("유의어 관계 수정 진행: '{}' ↔ '{}' 삭제 후 새로운 관계 생성",
            oldTerm1Name, oldTerm2Name);

        // 기존 유의어 관계 삭제
        termSynonymRepository.delete(existingSynonym);

        // 새로운 유의어 관계 생성
        return addSynonym(newTermId1, newTermId2);
    }

    /**
     * term 문자열로 유의어 관계 수정
     * 존재하지 않는 term은 자동으로 생성
     *
     * @param synonymId 수정할 TermSynonym ID
     * @param termStr1 첫 번째 term 문자열
     * @param termStr2 두 번째 term 문자열
     * @return 수정된 TermSynonym
     */
    @Transactional
    public TermSynonym updateSynonymByTermString(Long synonymId, String termStr1, String termStr2) {
        // 기존 유의어 확인
        TermSynonym existingSynonym = termSynonymRepository.findById(synonymId)
            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유의어 관계입니다. ID: " + synonymId));

        log.info("유의어 수정 (문자열 기반) - synonymId: {}, 새로운: ['{}', '{}']",
            synonymId, termStr1, termStr2);

        // 기존 유의어 삭제
        termSynonymRepository.delete(existingSynonym);

        // Term 조회 또는 생성
        Term term1 = termRepository.findByTermAndTermType(termStr1.toLowerCase(), "NNG")
            .orElseGet(() -> createNewTerm(termStr1));
        Term term2 = termRepository.findByTermAndTermType(termStr2.toLowerCase(), "NNG")
            .orElseGet(() -> createNewTerm(termStr2));

        // 새로운 유의어 생성
        return addSynonym(term1.getId(), term2.getId());
    }

    /**
     * 혼합 형태로 유의어 관계 수정 (termId + termString)
     *
     * @param synonymId 수정할 TermSynonym ID
     * @param termId1 첫 번째 term ID (nullable)
     * @param termStr1 첫 번째 term 문자열 (nullable)
     * @param termId2 두 번째 term ID (nullable)
     * @param termStr2 두 번째 term 문자열 (nullable)
     * @return 수정된 TermSynonym
     */
    @Transactional
    public TermSynonym updateSynonymMixed(Long synonymId, Long termId1, String termStr1,
                                          Long termId2, String termStr2) {
        // 기존 유의어 확인
        TermSynonym existingSynonym = termSynonymRepository.findById(synonymId)
            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유의어 관계입니다. ID: " + synonymId));

        log.info("유의어 수정 (혼합 형태) - synonymId: {}", synonymId);

        // 기존 유의어 삭제
        termSynonymRepository.delete(existingSynonym);

        // Term 1 처리
        Term term1;
        if (termId1 != null) {
            term1 = termRepository.findById(termId1)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 term입니다. ID: " + termId1));
        } else if (termStr1 != null && !termStr1.trim().isEmpty()) {
            term1 = termRepository.findByTermAndTermType(termStr1.trim().toLowerCase(), "NNG")
                .orElseGet(() -> createNewTerm(termStr1.trim()));
        } else {
            throw new IllegalArgumentException("term1에 대한 ID 또는 문자열이 필요합니다.");
        }

        // Term 2 처리
        Term term2;
        if (termId2 != null) {
            term2 = termRepository.findById(termId2)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 term입니다. ID: " + termId2));
        } else if (termStr2 != null && !termStr2.trim().isEmpty()) {
            term2 = termRepository.findByTermAndTermType(termStr2.trim().toLowerCase(), "NNG")
                .orElseGet(() -> createNewTerm(termStr2.trim()));
        } else {
            throw new IllegalArgumentException("term2에 대한 ID 또는 문자열이 필요합니다.");
        }

        // 새로운 유의어 생성
        return addSynonym(term1.getId(), term2.getId());
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
     * 두 term ID로 유의어 관계 조회
     */
    public Optional<TermSynonym> findSynonymByTermIds(Long termId1, Long termId2) {
        return termSynonymRepository.findByTermIds(termId1, termId2);
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
