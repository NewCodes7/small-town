package com.newcodes7.small_town.corporation.repository;

import com.newcodes7.small_town.corporation.entity.CorporationIndustry;
import com.newcodes7.small_town.global.entity.Corporation;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Corporation 엔티티에 대한 동적 쿼리를 생성하는 Specification 클래스
 */
public class CorporationSpecification {

    /**
     * 다양한 필터 조건을 조합하여 Specification 생성
     *
     * @param search 검색어 (기업명)
     * @param filter 필터 타입 ("all", "blog", "youtube")
     * @param industryIds Industry ID 리스트
     * @return 조합된 Specification
     */
    public static Specification<Corporation> withFilters(String search, String filter, List<Integer> industryIds) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 소프트 삭제되지 않은 것만 조회
            predicates.add(criteriaBuilder.isNull(root.get("deletedAt")));

            // 검색어 필터 (기업명, 대체 기업명, 자모 분리, 초성)
            if (search != null && !search.trim().isEmpty()) {
                Predicate namePredicate = criteriaBuilder.like(
                    root.get("name"),
                    "%" + search.trim() + "%"
                );
                Predicate alternateNamePredicate = criteriaBuilder.like(
                    root.get("alternateName"),
                    "%" + search.trim() + "%"
                );
                Predicate decomposedNamePredicate = criteriaBuilder.like(
                    root.get("decomposedName"),
                    "%" + search.trim() + "%"
                );
                Predicate decomposedAlternateNamePredicate = criteriaBuilder.like(
                    root.get("decomposedAlternateName"),
                    "%" + search.trim() + "%"
                );
                Predicate chosungNamePredicate = criteriaBuilder.like(
                    root.get("chosungName"),
                    "%" + search.trim() + "%"
                );
                Predicate chosungAlternateNamePredicate = criteriaBuilder.like(
                    root.get("chosungAlternateName"),
                    "%" + search.trim() + "%"
                );
                predicates.add(criteriaBuilder.or(
                    namePredicate,
                    alternateNamePredicate,
                    decomposedNamePredicate,
                    decomposedAlternateNamePredicate,
                    chosungNamePredicate,
                    chosungAlternateNamePredicate
                ));
            }

            // 블로그/유튜브 필터
            if ("blog".equals(filter)) {
                predicates.add(criteriaBuilder.isNotNull(root.get("blogLink")));
                predicates.add(criteriaBuilder.notEqual(root.get("blogLink"), ""));
            } else if ("youtube".equals(filter)) {
                predicates.add(criteriaBuilder.isNotNull(root.get("youtubeUrl")));
                predicates.add(criteriaBuilder.notEqual(root.get("youtubeUrl"), ""));
            }

            // Industry 필터
            if (industryIds != null && !industryIds.isEmpty()) {
                // JOIN을 통한 Industry 필터링
                Join<Corporation, CorporationIndustry> corporationIndustryJoin =
                    root.join("corporationIndustries", JoinType.INNER);
                predicates.add(corporationIndustryJoin.get("industry").get("id").in(industryIds));

                // DISTINCT 설정 (중복 제거)
                query.distinct(true);
            }

            // corporationIndustries FETCH JOIN 제거 - OOM 방지
            // 페이지네이션과 컬렉션 FETCH JOIN 조합 시 전체 데이터가 메모리에 로드됨

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
