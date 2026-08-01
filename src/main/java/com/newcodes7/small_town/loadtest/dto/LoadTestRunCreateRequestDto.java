package com.newcodes7.small_town.loadtest.dto;

import com.newcodes7.small_town.loadtest.entity.LoadTestRun.ExecutionType;
import com.newcodes7.small_town.loadtest.entity.LoadTestRun.TargetEnv;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoadTestRunCreateRequestDto {
    private String scenario;
    private ExecutionType executionType;
    private TargetEnv targetEnv;
    private Integer taskCount;
    private Integer totalRate;
    private Integer totalVus;
    private String duration;
    private String extraEnv;
    private boolean publicMode;
    private String triggeredBy;
}
