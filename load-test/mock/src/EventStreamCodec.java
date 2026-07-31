import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * AWS eventstream(application/vnd.amazon.eventstream) 프레임 인코더.
 *
 * 프레임 구조 (모두 big-endian):
 *   [4B total_length][4B headers_length][4B prelude CRC32]  ← prelude
 *   [headers][payload]
 *   [4B message CRC32 (prelude~payload 전체)]
 * 헤더 인코딩: name_len(1B) | name | value_type(1B, 7=string) | value_len(2B) | value
 *
 * SDK의 ConverseStream 언마샬러는 :message-type=event / :event-type / :content-type=application/json
 * 세 헤더와 CRC 둘 다 검증하므로 정확히 맞춰야 한다 (검증은 BedrockMockServerSdkIT).
 */
public final class EventStreamCodec {

    private static final byte HEADER_TYPE_STRING = 7;

    private EventStreamCodec() {
    }

    /** eventType 이벤트 프레임 하나를 인코딩한다. payload는 JSON 문자열. */
    public static byte[] event(String eventType, String jsonPayload) {
        byte[] headers = concat(
                header(":event-type", eventType),
                header(":content-type", "application/json"),
                header(":message-type", "event"));
        byte[] payload = jsonPayload.getBytes(StandardCharsets.UTF_8);

        int totalLength = 12 + headers.length + payload.length + 4;
        ByteBuffer frame = ByteBuffer.allocate(totalLength);
        frame.putInt(totalLength);
        frame.putInt(headers.length);
        CRC32 preludeCrc = new CRC32();
        preludeCrc.update(frame.array(), 0, 8);
        frame.putInt((int) preludeCrc.getValue());
        frame.put(headers);
        frame.put(payload);
        CRC32 messageCrc = new CRC32();
        messageCrc.update(frame.array(), 0, totalLength - 4);
        frame.putInt((int) messageCrc.getValue());
        return frame.array();
    }

    private static byte[] header(String name, String value) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + nameBytes.length + 1 + 2 + valueBytes.length);
        buffer.put((byte) nameBytes.length);
        buffer.put(nameBytes);
        buffer.put(HEADER_TYPE_STRING);
        buffer.putShort((short) valueBytes.length);
        buffer.put(valueBytes);
        return buffer.array();
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] array : arrays) {
            total += array.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(total);
        for (byte[] array : arrays) {
            buffer.put(array);
        }
        return buffer.array();
    }
}
