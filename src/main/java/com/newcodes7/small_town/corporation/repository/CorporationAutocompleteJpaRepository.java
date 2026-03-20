package com.newcodes7.small_town.corporation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.global.entity.Corporation;

import jakarta.persistence.QueryHint;

public interface CorporationAutocompleteJpaRepository extends JpaRepository<Corporation, Long> {

    interface CorporationAutocompleteProjection {
        Long getId();
        String getName();
        String getAlternateName();
        String getLogoUrl();
        String getLogoS3Url();
        String getLogoFilename();
        String getDecomposedName();
        String getDecomposedAlternateName();
    }

    @Transactional(readOnly = true)
    @QueryHints(@QueryHint(name = "org.hibernate.readOnly", value = "true"))
    @Query(value = """
        SELECT DISTINCT id, name, alternate_name, logo_url, logo_s3_url, logo_filename, decomposed_name, decomposed_alternate_name
        FROM (
            SELECT c.id, c.name, c.alternate_name, c.logo_url, c.logo_s3_url, c.logo_filename, c.decomposed_name, c.decomposed_alternate_name
            FROM corporation c
            WHERE c.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM article a WHERE a.corporation_id = c.id AND a.deleted_at IS NULL)
              AND (LOWER(c.name) LIKE :query1 OR LOWER(c.alternate_name) LIKE :query1
                   OR LOWER(c.decomposed_name) LIKE :query1 OR LOWER(c.decomposed_alternate_name) LIKE :query1
                   OR LOWER(c.chosung_name) LIKE :query1 OR LOWER(c.chosung_alternate_name) LIKE :query1)
            UNION ALL
            SELECT c.id, c.name, c.alternate_name, c.logo_url, c.logo_s3_url, c.logo_filename, c.decomposed_name, c.decomposed_alternate_name
            FROM corporation c
            WHERE c.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM article a WHERE a.corporation_id = c.id AND a.deleted_at IS NULL)
              AND (LOWER(c.name) LIKE :query2 OR LOWER(c.alternate_name) LIKE :query2
                   OR LOWER(c.decomposed_name) LIKE :query2 OR LOWER(c.decomposed_alternate_name) LIKE :query2
                   OR LOWER(c.chosung_name) LIKE :query2 OR LOWER(c.chosung_alternate_name) LIKE :query2)
        ) AS combined
        ORDER BY name ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<CorporationAutocompleteProjection> findAutocompleteCorporationsWithTwoPatterns(
        @Param("query1") String query1, @Param("query2") String query2, @Param("limit") int limit);
}
