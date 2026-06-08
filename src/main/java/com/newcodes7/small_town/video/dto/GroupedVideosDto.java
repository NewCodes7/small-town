package com.newcodes7.small_town.video.dto;

import java.util.List;

import com.newcodes7.small_town.corporation.dto.CorporationDto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class GroupedVideosDto implements VideoResponseDto {
    private CorporationDto corporation;
    private List<VideoListResponseDto> videos;
}
