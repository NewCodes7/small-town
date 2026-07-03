package com.newcodes7.small_town.term.service;

import static org.assertj.core.api.Assertions.*;

import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.entity.TermSynonym;
import com.newcodes7.small_town.term.repository.TermRepository;
import com.newcodes7.small_town.term.repository.TermSynonymRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * TermSynonymService 통합 테스트
 * Term 동의어 관계 관리 기능 검증
 */
public class TermSynonymServiceTest extends IntegrationTestBase {

    @Autowired
    private TermSynonymService termSynonymService;

    @Autowired
    private TermRepository termRepository;

    @Autowired
    private TermSynonymRepository termSynonymRepository;

    private Term term1;
    private Term term2;
    private Term term3;

    @BeforeEach
    void setUp() {
        term1 = Term.builder()
                .term("자바")
                .termType("NNG")
                .build();
        term1 = termRepository.save(term1);

        term2 = Term.builder()
                .term("Java")
                .termType("SL")
                .build();
        term2 = termRepository.save(term2);

        term3 = Term.builder()
                .term("파이썬")
                .termType("NNG")
                .build();
        term3 = termRepository.save(term3);
    }

    @Test
    @DisplayName("동의어 추가 - 성공")
    void addSynonym_Success() {
        // when
        TermSynonym synonym = termSynonymService.addSynonym(term1.getId(), term2.getId());

        // then
        assertThat(synonym).isNotNull();
        assertThat(synonym.getTerm().getId()).isLessThan(synonym.getSynonymTerm().getId());

        // hasTranslation이 true로 설정되었는지 확인
        Term updatedTerm1 = termRepository.findById(term1.getId()).orElseThrow();
        Term updatedTerm2 = termRepository.findById(term2.getId()).orElseThrow();
        assertThat(updatedTerm1.getHasTranslation()).isTrue();
        assertThat(updatedTerm2.getHasTranslation()).isTrue();
    }

    @Test
    @DisplayName("동의어 추가 - 같은 term끼리는 불가")
    void addSynonym_SameTerm_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> termSynonymService.addSynonym(term1.getId(), term1.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 term");
    }

    @Test
    @DisplayName("동의어 추가 - 존재하지 않는 term")
    void addSynonym_NonExistentTerm_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> termSynonymService.addSynonym(999999L, term1.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는");
    }

    @Test
    @DisplayName("동의어 추가 - 이미 존재하는 관계")
    void addSynonym_AlreadyExists_ThrowsException() {
        // given
        termSynonymService.addSynonym(term1.getId(), term2.getId());

        // when & then
        assertThatThrownBy(() -> termSynonymService.addSynonym(term1.getId(), term2.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 유의어");
    }

    @Test
    @DisplayName("동의어 추가 - term_id < synonym_term_id 규칙 적용")
    void addSynonym_OrderRule() {
        // when - ID가 큰 것을 먼저 넣어도 자동 정렬되어야 함
        TermSynonym synonym = termSynonymService.addSynonym(term2.getId(), term1.getId());

        // then
        assertThat(synonym.getTerm().getId()).isLessThan(synonym.getSynonymTerm().getId());
    }

    @Test
    @DisplayName("문자열로 동의어 추가 - 기존 term 사용")
    void addSynonymByTermString_ExistingTerms() {
        // when
        TermSynonym synonym = termSynonymService.addSynonymByTermString("자바", "Java");

        // then
        assertThat(synonym).isNotNull();
        assertThat(synonym.getTerm().getTerm()).isIn("자바", "java");
        assertThat(synonym.getSynonymTerm().getTerm()).isIn("자바", "java");
    }

    @Test
    @DisplayName("문자열로 동의어 추가 - 새 term 자동 생성")
    void addSynonymByTermString_CreatesNewTerms() {
        // when
        TermSynonym synonym = termSynonymService.addSynonymByTermString("새단어1", "새단어2");

        // then
        assertThat(synonym).isNotNull();

        // 새로운 term들이 생성되었는지 확인
        assertThat(termRepository.findByTermAndTermType("새단어1", "NNG")).isPresent();
        assertThat(termRepository.findByTermAndTermType("새단어2", "NNG")).isPresent();
    }

    @Test
    @DisplayName("동의어 삭제 - ID로 삭제")
    void deleteSynonym_ById() {
        // given
        TermSynonym synonym = termSynonymService.addSynonym(term1.getId(), term2.getId());
        Long synonymId = synonym.getId();

        // when
        termSynonymService.deleteSynonym(synonymId);

        // then
        assertThat(termSynonymRepository.findById(synonymId)).isEmpty();
    }

    @Test
    @DisplayName("동의어 삭제 - term ID로 삭제")
    void deleteSynonymByTermIds() {
        // given
        termSynonymService.addSynonym(term1.getId(), term2.getId());

        // when
        termSynonymService.deleteSynonymByTermIds(term1.getId(), term2.getId());

        // then
        assertThat(termSynonymRepository.existsByTermIds(term1.getId(), term2.getId())).isFalse();
    }

    @Test
    @DisplayName("동의어 삭제 - 존재하지 않는 관계")
    void deleteSynonymByTermIds_NotExists_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> termSynonymService.deleteSynonymByTermIds(term1.getId(), term3.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않습니다");
    }

    @Test
    @DisplayName("동의어 term ID 조회 - 양방향")
    void getSynonymTermIds() {
        // given
        termSynonymService.addSynonym(term1.getId(), term2.getId());
        termSynonymService.addSynonym(term1.getId(), term3.getId());

        // when
        List<Long> synonymIds = termSynonymService.getSynonymTermIds(term1.getId());

        // then
        assertThat(synonymIds).hasSize(3); // term1 + term2 + term3
        assertThat(synonymIds).contains(term1.getId(), term2.getId(), term3.getId());
    }

    @Test
    @org.junit.jupiter.api.Disabled("Hibernate CASE statement issue - needs investigation")
    @DisplayName("동의어 term 조회")
    void getSynonymTerms() {
        // given
        termSynonymService.addSynonym(term1.getId(), term2.getId());

        // when
        List<Term> synonyms = termSynonymService.getSynonymTerms(term1.getId());

        // then
        assertThat(synonyms).hasSize(1);
        assertThat(synonyms.get(0).getId()).isEqualTo(term2.getId());
    }

    @Test
    @DisplayName("동의어 관계 조회")
    void getSynonymRelations() {
        // given
        termSynonymService.addSynonym(term1.getId(), term2.getId());
        termSynonymService.addSynonym(term1.getId(), term3.getId());

        // when
        List<TermSynonym> relations = termSynonymService.getSynonymRelations(term1.getId());

        // then
        assertThat(relations).hasSize(2);
    }

    @Test
    @DisplayName("모든 동의어 관계 조회")
    void getAllSynonyms() {
        // given
        termSynonymService.addSynonym(term1.getId(), term2.getId());
        termSynonymService.addSynonym(term2.getId(), term3.getId());

        // when
        List<TermSynonym> allSynonyms = termSynonymService.getAllSynonyms();

        // then
        assertThat(allSynonyms).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("동의어 관계 존재 확인")
    void existsSynonym() {
        // given
        termSynonymService.addSynonym(term1.getId(), term2.getId());

        // when & then
        assertThat(termSynonymService.existsSynonym(term1.getId(), term2.getId())).isTrue();
        assertThat(termSynonymService.existsSynonym(term1.getId(), term3.getId())).isFalse();
    }

    @Test
    @DisplayName("여러 term ID를 동의어 포함하여 확장")
    void expandTermIdsWithSynonyms() {
        // given
        termSynonymService.addSynonym(term1.getId(), term2.getId());

        // when
        List<Long> expandedIds = termSynonymService.expandTermIdsWithSynonyms(
                List.of(term1.getId())
        );

        // then
        assertThat(expandedIds).contains(term1.getId(), term2.getId());
        assertThat(expandedIds).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("동의어 수정 - 성공")
    void updateSynonym_Success() {
        // given
        TermSynonym original = termSynonymService.addSynonym(term1.getId(), term2.getId());

        // when - term2 대신 term3으로 변경
        TermSynonym updated = termSynonymService.updateSynonym(
                original.getId(), term1.getId(), term3.getId()
        );

        // then
        assertThat(updated.getSynonymTerm().getId()).isEqualTo(term3.getId());

        // 기존 관계는 삭제되어야 함
        assertThat(termSynonymRepository.findById(original.getId())).isEmpty();
    }

    @Test
    @DisplayName("동의어 수정 - 같은 관계로 수정하면 그대로 유지")
    void updateSynonym_SameRelation_NoChange() {
        // given
        TermSynonym original = termSynonymService.addSynonym(term1.getId(), term2.getId());
        Long originalId = original.getId();

        // when - 같은 관계로 수정
        TermSynonym result = termSynonymService.updateSynonym(
                originalId, term1.getId(), term2.getId()
        );

        // then - ID가 동일해야 함 (변경 없음)
        assertThat(result.getId()).isEqualTo(originalId);
    }
}
