package com.newcodes7.small_town.loadtest.service;

import static org.assertj.core.api.Assertions.*;

import com.newcodes7.small_town.loadtest.entity.LoadTestRun.ExecutionType;
import com.newcodes7.small_town.loadtest.entity.LoadTestRun.TargetEnv;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class LoadTestCommandBuilderTest {

    private final LoadTestCommandBuilder commandBuilder = new LoadTestCommandBuilder();

    @Test
    @DisplayName("local + LOCAL_DIRECT — BASE_URL 접두사 없이 run-local.sh, testid 자동 생성")
    void local_directTarget_생성() {
        // when
        LoadTestCommandBuilder.Result result = commandBuilder.build(
                "search-hybrid", ExecutionType.LOCAL, TargetEnv.LOCAL_DIRECT,
                null, null, null, null, "RATE=5\nDURATION=1m", false);

        // then
        assertThat(result.command())
                .startsWith("./scripts/run-local.sh search-hybrid -e TEST_RUN_ID=lt-")
                .contains(" -e RATE=5")
                .contains(" -e DURATION=1m")
                .doesNotContain("BASE_URL=");
        assertThat(result.testRunId()).matches("lt-\\d{8}-\\d{6}");
    }

    @Test
    @DisplayName("local + LOCAL_NGINX — BASE_URL=http://host.docker.internal 접두사 붙음")
    void local_nginxTarget_생성() {
        // when
        LoadTestCommandBuilder.Result result = commandBuilder.build(
                "baseline", ExecutionType.LOCAL, TargetEnv.LOCAL_NGINX,
                null, null, null, null, null, false);

        // then
        assertThat(result.command())
                .startsWith("BASE_URL=http://host.docker.internal ./scripts/run-local.sh baseline");
        assertThat(result.testRunId()).isNotNull();
    }

    @Test
    @DisplayName("local + PRODUCTION — BASE_URL=https://newcodes.net 접두사 붙음")
    void local_productionTarget_생성() {
        // when
        LoadTestCommandBuilder.Result result = commandBuilder.build(
                "autocomplete", ExecutionType.LOCAL, TargetEnv.PRODUCTION,
                null, null, null, null, null, false);

        // then
        assertThat(result.command()).startsWith("BASE_URL=https://newcodes.net ./scripts/run-local.sh autocomplete");
    }

    @Test
    @DisplayName("fargate — run-task.sh 플래그 조립, TEST_RUN_ID는 절대 넣지 않음")
    void fargate_전체파라미터_생성() {
        // when
        LoadTestCommandBuilder.Result result = commandBuilder.build(
                "rag-answer", ExecutionType.FARGATE, TargetEnv.PRODUCTION,
                2, 40, 10, "10m", "MODE=cache-miss", false);

        // then
        assertThat(result.command())
                .isEqualTo("cd fargate && ./run-task.sh -s rag-answer -n 2 -r 40 -v 10 -d 10m -e MODE=cache-miss");
        assertThat(result.testRunId()).isNull();
    }

    @Test
    @DisplayName("fargate — null 파라미터는 플래그 생략, --no-bypass는 뒤에 붙음")
    void fargate_일부파라미터_생략_public모드() {
        // when
        LoadTestCommandBuilder.Result result = commandBuilder.build(
                "rate-limit-check", ExecutionType.FARGATE, TargetEnv.PRODUCTION,
                null, null, null, null, null, true);

        // then
        assertThat(result.command())
                .isEqualTo("cd fargate && ./run-task.sh -s rate-limit-check --no-bypass");
    }

    @Test
    @DisplayName("extraEnv 빈 줄은 건너뛰고 각 줄 앞뒤 공백은 제거")
    void extraEnv_공백_처리() {
        // when
        LoadTestCommandBuilder.Result result = commandBuilder.build(
                "spike", ExecutionType.LOCAL, TargetEnv.LOCAL_DIRECT,
                null, null, null, null, "\n  RATE=5  \n\n  MODE=cache-hit\n", false);

        // then
        assertThat(result.command()).contains(" -e RATE=5").contains(" -e MODE=cache-hit");
    }

    @Test
    @DisplayName("잘못된 형식의 extraEnv 줄은 400으로 거부")
    void extraEnv_잘못된형식_예외() {
        // when / then
        assertThatThrownBy(() -> commandBuilder.build(
                        "baseline", ExecutionType.LOCAL, TargetEnv.LOCAL_DIRECT,
                        null, null, null, null, "이것은잘못된형식", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("잘못된 env 형식");
    }
}
