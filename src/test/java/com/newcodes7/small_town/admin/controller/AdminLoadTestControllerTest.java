package com.newcodes7.small_town.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.loadtest.dto.LoadTestRunCreateRequestDto;
import com.newcodes7.small_town.loadtest.dto.LoadTestRunUpdateRequestDto;
import com.newcodes7.small_town.loadtest.entity.LoadTestRun;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * AdminLoadTestController 통합 테스트 — 실제 DB(V1_35 마이그레이션 적용)에 대해
 * 생성/조회/수정/삭제 흐름 검증. HTTP/보안 레이어는 다른 admin 컨트롤러와 동일하게
 * 이미 검증된 표준 경로이므로, AdminRagControllerTest 패턴처럼 컨트롤러를 직접 호출한다.
 */
class AdminLoadTestControllerTest extends IntegrationTestBase {

    @Autowired
    private AdminLoadTestController controller;

    @Test
    @DisplayName("local 생성 → testid 자동 채워짐, fargate 생성 → testid는 null")
    void create_local과fargate_testRunId차이() {
        // when
        LoadTestRunCreateRequestDto localReq = new LoadTestRunCreateRequestDto();
        localReq.setScenario("search-hybrid");
        localReq.setExecutionType(LoadTestRun.ExecutionType.LOCAL);
        localReq.setTargetEnv(LoadTestRun.TargetEnv.LOCAL_DIRECT);
        localReq.setExtraEnv("RATE=5");
        ResponseEntity<Map<String, Object>> localRes = controller.create(localReq);

        LoadTestRunCreateRequestDto fargateReq = new LoadTestRunCreateRequestDto();
        fargateReq.setScenario("rag-answer");
        fargateReq.setExecutionType(LoadTestRun.ExecutionType.FARGATE);
        fargateReq.setTargetEnv(LoadTestRun.TargetEnv.PRODUCTION);
        fargateReq.setTaskCount(2);
        fargateReq.setTotalRate(40);
        ResponseEntity<Map<String, Object>> fargateRes = controller.create(fargateReq);

        // then
        assertThat(localRes.getBody().get("testRunId")).asString().startsWith("lt-");
        assertThat(localRes.getBody().get("generatedCommand"))
                .asString()
                .contains("./scripts/run-local.sh search-hybrid")
                .contains(" -e RATE=5");
        assertThat(localRes.getBody().get("status")).isEqualTo(LoadTestRun.Status.PLANNED);

        assertThat(fargateRes.getBody().get("testRunId")).isNull();
        assertThat(fargateRes.getBody().get("generatedCommand"))
                .asString()
                .isEqualTo("cd fargate && ./run-task.sh -s rag-answer -n 2 -r 40");
    }

    @Test
    @DisplayName("생성 → 조회 → 수정(testid/Grafana/지표 기록) → 목록 통계 반영 → 삭제")
    void create_detail_update_delete_전체흐름() {
        // given
        LoadTestRunCreateRequestDto createReq = new LoadTestRunCreateRequestDto();
        createReq.setScenario("baseline");
        createReq.setExecutionType(LoadTestRun.ExecutionType.FARGATE);
        createReq.setTargetEnv(LoadTestRun.TargetEnv.PRODUCTION);
        createReq.setTaskCount(1);
        Long id = (Long) controller.create(createReq).getBody().get("id");

        // when: 실행 후 기록 갱신
        LoadTestRunUpdateRequestDto updateReq = new LoadTestRunUpdateRequestDto();
        updateReq.setTestRunId("20260801-120000");
        updateReq.setStatus(LoadTestRun.Status.COMPLETED);
        updateReq.setPassFail(true);
        updateReq.setStartedAt(LocalDateTime.of(2026, 8, 1, 12, 0));
        updateReq.setFinishedAt(LocalDateTime.of(2026, 8, 1, 12, 5));
        updateReq.setGrafanaUrl("/admin/monitor/d/small-town-load-test/?var-testid=20260801-120000");
        updateReq.setRecordedMetrics("p95=350ms, error_rate=0%");
        ResponseEntity<Map<String, Object>> updated = controller.update(id, updateReq);

        // then
        assertThat(updated.getBody().get("testRunId")).isEqualTo("20260801-120000");
        assertThat(updated.getBody().get("status")).isEqualTo(LoadTestRun.Status.COMPLETED);
        assertThat(updated.getBody().get("passFail")).isEqualTo(true);
        assertThat(updated.getBody().get("recordedMetrics")).isEqualTo("p95=350ms, error_rate=0%");

        // detail 재조회로도 동일하게 반영되는지 확인
        ResponseEntity<Map<String, Object>> detail = controller.detail(id);
        assertThat(detail.getBody().get("grafanaUrl"))
                .isEqualTo("/admin/monitor/d/small-town-load-test/?var-testid=20260801-120000");

        // 목록/통계에도 반영
        String view = controller.list(null, null, null, null, 0, 30, new org.springframework.ui.ExtendedModelMap());
        assertThat(view).isEqualTo("admin/load-test/index");

        // 삭제 후 조회 시 404
        controller.delete(id);
        assertThatThrownBy(() -> controller.detail(id)).isInstanceOf(ResponseStatusException.class);
    }
}
