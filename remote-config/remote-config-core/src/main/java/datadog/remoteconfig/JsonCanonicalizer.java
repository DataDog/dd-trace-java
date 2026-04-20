package datadog.remoteconfig;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class JsonCanonicalizer {
  private static final byte[] TRUE_CONSTANT = {'t', 'r', 'u', 'e'};
  private static final byte[] FALSE_CONSTANT = {'f', 'a', 'l', 's', 'e'};
  private static final byte[] NULL_CONSTANT = {'n', 'u', 'l', 'l'};

  public static byte[] canonicalize(Map<String, Object> map) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    serialize(baos, map);
    return baos.toByteArray();
  }

  private static void serialize(ByteArrayOutputStream os, Object o) {
    if (o == null) {
      os.write(NULL_CONSTANT, 0, NULL_CONSTANT.length);
    } else if (o instanceof String) {
      serialize(os, (String) o);
    } else if (o instanceof Number) {
      serialize(os, ((Number) o).longValue());
    } else if (o instanceof Boolean) {
      serialize(os, (Boolean) o);
    } else if (o instanceof List) {
      serialize(os, (List) o);
    } else if (o instanceof Map) {
      serialize(os, (Map) o);
    } else {
      throw new RuntimeException("Unexpected type for: " + o);
    }
  }

  private static void serialize(ByteArrayOutputStream os, String s) {
    os.write('"');
    s.codePoints()
        .forEach(
            codepoint -> {
              if (codepoint == '\\' || codepoint == '"') {
                os.write('\\');
              }
              if (codepoint < 0x80) {
                os.write(codepoint);
              } else if (codepoint < 0x800) {
                os.write(0xc0 | (codepoint >> 6));
                os.write(0x80 | (codepoint & 0x3f));
              } else if (codepoint < 0x10000) {
                os.write(0xe0 | ((codepoint >> 12)));
                os.write(0x80 | ((codepoint >> 6) & 0x3f));
                os.write(0x80 | (codepoint & 0x3f));
              } else {
                os.write(0xf0 | ((codepoint >> 18)));
                os.write(0x80 | ((codepoint >> 12) & 0x3f));
                os.write(0x80 | ((codepoint >> 6) & 0x3f));
                os.write(0x80 | (codepoint & 0x3f));
              }
            });
    os.write('"');
  }

  private static void serialize(ByteArrayOutputStream os, long l) {
    byte[] bytes = Long.toString(l).getBytes(StandardCharsets.US_ASCII);
    os.write(bytes, 0, bytes.length);
  }

  private static void serialize(ByteArrayOutputStream os, Boolean b) {
    byte[] arr = b.booleanValue() ? TRUE_CONSTANT : FALSE_CONSTANT;
    os.write(arr, 0, arr.length);
  }

  private static void serialize(ByteArrayOutputStream os, List<Object> list) {
    os.write('[');
    for (int i = 0; i < list.size(); i++) {
      if (i != 0) {
        os.write(',');
      }
      serialize(os, list.get(i));
    }
    os.write(']');
  }

  private static void serialize(ByteArrayOutputStream os, Map<String, Object> map) {
    os.write('{');

    boolean[] first = new boolean[] {true};
    map.entrySet().stream()
        // the canonical json spec only says "keys are lexicographically sorted"
        .sorted((e1, e2) -> String.CASE_INSENSITIVE_ORDER.compare(e1.getKey(), e2.getKey()))
        .forEach(
            e -> {
              if (first[0]) {
                first[0] = false;
              } else {
                os.write(',');
              }

              serialize(os, e.getKey());
              os.write(':');
              serialize(os, e.getValue());
            });

    os.write('}');
  }
}
