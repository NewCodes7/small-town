package software.amazon.eventstream;

import static org.assertj.core.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MessageDecoder} 섀도잉 가드.
 *
 * <p>그 클래스는 {@code software.amazon.eventstream:eventstream:1.0.1}의 동명 클래스를
 * 클래스패스 섀도잉으로 대체한 <b>포크</b>다. 원본은 무인자 생성자에서 2 MiB를 무조건 할당하는데,
 * AWS SDK가 ConverseStream 호출마다 이 디코더를 새로 만들어 <b>동시 스트림 1개당 2 MiB</b>가
 * 고정 비용이 된다 (2026-08-27 JFR 실측, load-test/results/2026-08-27-rag-ladder.md 9장).
 *
 * <p>포크는 두 가지 방식으로 <b>조용히</b> 깨질 수 있고, 아래 테스트가 각각을 막는다:
 * <ol>
 *   <li><b>섀도잉이 풀린다</b> — 빌드/패키징이 바뀌어 라이브러리 클래스가 다시 이기면
 *       버퍼가 2 MiB로 돌아가는데 <b>아무 에러도 안 난다.</b> 1번이 잡는다</li>
 *   <li><b>디코딩이 깨진다</b> — 초기 버퍼를 줄인 탓에 큰 메시지를 못 읽으면 스트리밍이
 *       망가진다. 2~5번이 원본과 같은 동작(자동 확장 포함)을 고정한다</li>
 * </ol>
 *
 * <p>eventstream 의존성을 올릴 때 이 테스트가 깨지면 원본 소스와 대조해 로직 변경분을 반영할 것.
 */
class MessageDecoderShadowingTest {

    /** 원본 상수. 이 값이 나오면 섀도잉이 풀린 것이다. */
    private static final int UPSTREAM_BUFFER_SIZE = 2048 * 1024;

    private static Message message(String payload) {
        Map<String, HeaderValue> headers = new LinkedHashMap<>();
        headers.put(":message-type", HeaderValue.fromString("event"));
        headers.put(":event-type", HeaderValue.fromString("contentBlockDelta"));
        return new Message(headers, payload.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("섀도잉이 걸려 있다 — 초기 버퍼가 원본 2 MiB가 아니다")
    void 섀도잉_적용됨() {
        int actual = new MessageDecoder().currentBufferSize();

        assertThat(actual)
                .withFailMessage(
                        "초기 버퍼가 %d다. 2 MiB(%d)면 섀도잉이 풀려 라이브러리 클래스가 로드된 것이다 — "
                                + "BOOT-INF/classes가 BOOT-INF/lib보다 먼저 탐색되는지 확인할 것.",
                        actual, UPSTREAM_BUFFER_SIZE)
                .isNotEqualTo(UPSTREAM_BUFFER_SIZE);
        // 기본 32 KiB. -Dsmalltown.eventstream.initial-buffer-bytes 로 조정 가능하므로 상한만 본다.
        assertThat(actual).isPositive().isLessThan(UPSTREAM_BUFFER_SIZE);
    }

    @Test
    @DisplayName("작은 메시지 왕복 — 버퍼를 줄여도 디코딩은 원본과 동일하다")
    void 작은메시지_왕복() {
        Message original = message("{\"delta\":{\"text\":\"안녕\"}}");

        MessageDecoder decoder = new MessageDecoder();
        decoder.feed(original.toByteBuffer());
        List<Message> decoded = decoder.getDecodedMessages();

        assertThat(decoded).hasSize(1);
        assertThat(decoded.get(0)).isEqualTo(original);
    }

    @Test
    @DisplayName("초기 버퍼보다 큰 메시지도 읽는다 — 자동 확장이 살아 있다 (포크의 안전 근거)")
    void 큰메시지_자동확장() {
        MessageDecoder decoder = new MessageDecoder(msg -> { }, 64); // 일부러 아주 작게
        assertThat(decoder.currentBufferSize()).isEqualTo(64);

        Message big = message("x".repeat(100_000));
        decoder.feed(big.toByteBuffer());

        // 64바이트로 시작했지만 메시지 크기에 맞춰 재할당됐어야 한다
        assertThat(decoder.currentBufferSize()).isGreaterThan(100_000);
    }

    @Test
    @DisplayName("여러 메시지가 한 청크에 섞여 와도 순서대로 분리된다")
    void 다중메시지_분리() {
        Message a = message("first");
        Message b = message("second");
        ByteBuffer ba = a.toByteBuffer();
        ByteBuffer bb = b.toByteBuffer();
        ByteBuffer ab = ByteBuffer.allocate(ba.remaining() + bb.remaining());
        ab.put(ba).put(bb);
        ab.flip();

        MessageDecoder decoder = new MessageDecoder();
        decoder.feed(ab);

        assertThat(decoder.getDecodedMessages()).containsExactly(a, b);
    }

    @Test
    @DisplayName("메시지가 청크 경계로 쪼개져 도착해도 복원한다 (SSE 실제 도착 형태)")
    void 쪼개진청크_복원() {
        Message original = message("{\"delta\":{\"text\":\"조각난 전송\"}}");
        ByteBuffer full = original.toByteBuffer();
        byte[] bytes = new byte[full.remaining()];
        full.get(bytes);

        MessageDecoder decoder = new MessageDecoder();
        for (byte b : bytes) { // 1바이트씩 극단적으로 쪼개 먹인다
            decoder.feed(new byte[] {b});
        }

        assertThat(decoder.getDecodedMessages()).containsExactly(original);
    }
}
