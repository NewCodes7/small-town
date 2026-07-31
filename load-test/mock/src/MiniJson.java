import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 요청 파싱용 최소 JSON 파서 (의존성 0 유지 목적).
 * object→Map, array→List, string→String, number→Double, true/false→Boolean, null→null.
 * mock이 받는 요청(AWS SDK·RestTemplate이 생성한 유효한 JSON)만 다루므로 관대한 오류 처리로 충분하다.
 */
public final class MiniJson {

    private final String source;
    private int pos;

    private MiniJson(String source) {
        this.source = source;
    }

    public static Object parse(String json) {
        MiniJson parser = new MiniJson(json);
        parser.skipWhitespace();
        Object value = parser.readValue();
        return value;
    }

    /** 중첩 경로 조회 — Map 키(String) / List 인덱스(Integer) 혼용. 경로가 없으면 null. */
    public static Object at(Object node, Object... path) {
        Object current = node;
        for (Object step : path) {
            switch (current) {
                case Map<?, ?> map when step instanceof String key -> current = map.get(key);
                case List<?> list when step instanceof Integer index ->
                        current = index < list.size() ? list.get(index) : null;
                default -> {
                    return null;
                }
            }
        }
        return current;
    }

    public static String stringAt(Object node, Object... path) {
        return at(node, path) instanceof String s ? s : null;
    }

    /** JSON 문자열 값으로 출력하기 위한 이스케이프. */
    public static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private Object readValue() {
        char c = source.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // '{'
        skipWhitespace();
        if (source.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            pos++; // ':'
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char c = source.charAt(pos++);
            if (c == '}') {
                return map;
            }
            // c == ','
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // '['
        skipWhitespace();
        if (source.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = source.charAt(pos++);
            if (c == ']') {
                return list;
            }
            // c == ','
        }
    }

    private String readString() {
        StringBuilder sb = new StringBuilder();
        pos++; // '"'
        while (true) {
            char c = source.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char escape = source.charAt(pos++);
                switch (escape) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(source, pos, pos + 4, 16));
                        pos += 4;
                    }
                    default -> sb.append(escape); // '"', '\\', '/'
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object readLiteral(String literal, Object value) {
        pos += literal.length();
        return value;
    }

    private Double readNumber() {
        int start = pos;
        while (pos < source.length() && "-+.eE0123456789".indexOf(source.charAt(pos)) >= 0) {
            pos++;
        }
        return Double.parseDouble(source.substring(start, pos));
    }

    private void skipWhitespace() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
            pos++;
        }
    }
}
