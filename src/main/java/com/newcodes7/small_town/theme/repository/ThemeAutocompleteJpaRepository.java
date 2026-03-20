package com.newcodes7.small_town.theme.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.theme.entity.Theme;

import jakarta.persistence.QueryHint;

public interface ThemeAutocompleteJpaRepository extends JpaRepository<Theme, Long> {

    interface ThemeAutocompleteProjection {
        Long getId();
        String getName();
    }

    @Transactional(readOnly = true)
    @QueryHints(@QueryHint(name = "org.hibernate.readOnly", value = "true"))
    @Query(value = """
        SELECT id, name
        FROM theme
        WHERE deleted_at IS NULL AND is_active = true
          AND (LOWER(name) LIKE :query1 OR LOWER(decomposed_name) LIKE :query1
               OR LOWER(name) LIKE :query2 OR LOWER(decomposed_name) LIKE :query2)
        ORDER BY display_order ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<ThemeAutocompleteProjection> findAutocompleteThemesSimple(
        @Param("query1") String query1, @Param("query2") String query2, @Param("limit") int limit);
}
