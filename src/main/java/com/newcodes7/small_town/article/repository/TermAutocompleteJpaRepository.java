package com.newcodes7.small_town.article.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.global.entity.Term;

import jakarta.persistence.QueryHint;

public interface TermAutocompleteJpaRepository extends JpaRepository<Term, Long> {

    interface TermAutocompleteProjection {
        String getTerm();
        Long getTotalFrequency();
    }

    @Transactional(readOnly = true)
    @QueryHints(@QueryHint(name = "org.hibernate.readOnly", value = "true"))
    @Query(value = """
        SELECT term, MAX(total_frequency) AS total_frequency
        FROM (
            SELECT term, total_frequency FROM term
            WHERE term LIKE :query AND total_frequency > 0
            UNION ALL
            SELECT term, total_frequency FROM term
            WHERE decomposed_term LIKE :query AND total_frequency > 0
            UNION ALL
            SELECT term, total_frequency FROM term
            WHERE chosung LIKE :query AND total_frequency > 0
        ) AS combined
        GROUP BY term
        ORDER BY total_frequency DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<TermAutocompleteProjection> findAutocompleteTerms(
        @Param("query") String query, @Param("limit") int limit);
}
