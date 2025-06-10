package com.newcodes7.small_town.corporation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CorporationUpdateDto {
    
    @NotBlank(message = "기업명은 필수입니다.")
    @Size(max = 100, message = "기업명은 100자 이내여야 합니다.")
    private String name;
    
    private String homeLink;
    private String blogLink;
    private String crewLink;
    private String logoUrl;
    
    private List<Integer> industryIds;
}