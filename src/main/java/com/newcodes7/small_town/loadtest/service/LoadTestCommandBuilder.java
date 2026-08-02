package com.newcodes7.small_town.loadtest.service;

import com.newcodes7.small_town.loadtest.entity.LoadTestRun.ExecutionType;
import com.newcodes7.small_town.loadtest.entity.LoadTestRun.TargetEnv;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * load-test/scripts/run-local.sh, load-test/fargate/run-task.sh에 그대로 붙여넣을 수 있는
 * 커맨드 문자열을 조립한다. AWS/프로세스 실행은 하지 않는다 — Phase 1은 기록 관리 전용.
 *
 * <p>run-task.sh는 TEST_RUN_ID를 스크립트 내부에서 자체 생성해 containerOverrides.environment에
 * 주입하므로, 여기서 -e TEST_RUN_ID=...로 덮어쓰면 같은 키가 중복 등록돼 ECS RunTask가 실패할 수
 * 있다. 그래서 Fargate 커맨드는 TEST_RUN_ID를 넣지 않고, 실행 후 터미널에 출력되는 값을 사용자가
 * 직접 기록하도록 한다. run-local.sh는 반대로 TEST_RUN_ID를 자체 생성하지 않으므로 여기서 미리
 * 값을 정해 넣을 수 있다.
 */
@Service
public class LoadTestCommandBuilder {

    private static final DateTimeFormatter TEST_RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern EXTRA_ENV_LINE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=.*$");

    public record Result(String command, String testRunId) {}

    public Result build(
            String scenario,
            ExecutionType executionType,
            TargetEnv targetEnv,
            Integer taskCount,
            Integer totalRate,
            Integer totalVus,
            String duration,
            String extraEnv,
            boolean publicMode) {
        List<String> extraEnvLines = parseExtraEnv(extraEnv);
        return executionType == ExecutionType.LOCAL
                ? buildLocal(scenario, targetEnv, extraEnvLines)
                : buildFargate(
                        scenario, taskCount, totalRate, totalVus, duration, extraEnvLines, publicMode);
    }

    private Result buildLocal(String scenario, TargetEnv targetEnv, List<String> extraEnvLines) {
        String testRunId = "lt-" + LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(TEST_RUN_ID_FORMAT);

        StringBuilder cmd = new StringBuilder();
        if (targetEnv == TargetEnv.LOCAL_NGINX) {
            cmd.append("BASE_URL=http://host.docker.internal ");
        } else if (targetEnv == TargetEnv.PRODUCTION) {
            cmd.append("BASE_URL=https://newcodes.net ");
        }
        cmd.append("./scripts/run-local.sh ").append(scenario);
        cmd.append(" -e TEST_RUN_ID=").append(testRunId);
        extraEnvLines.forEach(line -> cmd.append(" -e ").append(line));

        return new Result(cmd.toString(), testRunId);
    }

    private Result buildFargate(
            String scenario,
            Integer taskCount,
            Integer totalRate,
            Integer totalVus,
            String duration,
            List<String> extraEnvLines,
            boolean publicMode) {
        StringBuilder cmd = new StringBuilder("cd fargate && ./run-task.sh -s ").append(scenario);
        if (taskCount != null) {
            cmd.append(" -n ").append(taskCount);
        }
        if (totalRate != null) {
            cmd.append(" -r ").append(totalRate);
        }
        if (totalVus != null) {
            cmd.append(" -v ").append(totalVus);
        }
        if (duration != null && !duration.isBlank()) {
            cmd.append(" -d ").append(duration.trim());
        }
        extraEnvLines.forEach(line -> cmd.append(" -e ").append(line));
        // publicMode="bypass 토큰 없이 실행"(rate-limit-check 전용) — 필드명은 과거 IP allowlist 시절 이름 그대로 유지
        if (publicMode) {
            cmd.append(" --no-bypass");
        }

        // testRunId는 실행 후 터미널 출력에서 사용자가 직접 복사해 기록한다.
        return new Result(cmd.toString(), null);
    }

    private List<String> parseExtraEnv(String extraEnv) {
        if (extraEnv == null || extraEnv.isBlank()) {
            return List.of();
        }
        return extraEnv
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(this::validateEnvLine)
                .toList();
    }

    private String validateEnvLine(String line) {
        if (!EXTRA_ENV_LINE.matcher(line).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "잘못된 env 형식입니다 (KEY=VALUE): " + line);
        }
        return line;
    }
}
