package com.newcodes7.small_town.corporation.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CorporationCreateDto {
    
    @NotBlank(message = "기업명은 필수입니다.")
    @Size(max = 100, message = "기업명은 100자 이내여야 합니다.")
    private String name;

    @Size(max = 100, message = "대체 기업명은 100자 이내여야 합니다.")
    private String alternateName;

    private Integer isDomestic;
    private String homeLink;
    private String blogLink;
    private String blogType; // DEFAULT, MEDIUM
    private String crewLink;
    private String logoUrl;
    private String youtubeUrl;

    // 블로그 글 파싱을 위해 필요한 속성
    private String baseUrl;
    private String article;
    private String title;
    private String link;
    private String thumbnail;
    private String publish;
    private String publishFormat;
    private String publishType; // OUTER (목록에서 추출) 또는 INNER (개별 글 접속해서 추출)
    private String innerPublishSelector; // INNER 타입일 때 개별 글 페이지에서 날짜를 추출할 셀렉터

    // 페이지네이션 설정
    private String paginationType;
    private String pageUrlPattern;
    private String nextPageSelector;
    private Integer maxPages;

    private List<Integer> industryIds;
}