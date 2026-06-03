package datadog.remoteconfig;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonCanonicalizerTests {

  @Test
  void mapKeysAreReordered() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("b", true);
    map.put("c", null);
    map.put("a", false);

    assertEquals(
        "{\"a\":false,\"b\":true,\"c\":null}",
        new String(JsonCanonicalizer.canonicalize(map), UTF_8));
  }

  @Test
  void utf8Encoding() {
    String str = "\u0000\u0080\u0800\uDBC0\uDC00";
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("a", str);

    assertEquals("{\"a\":\"" + str + "\"}", new String(JsonCanonicalizer.canonicalize(map), UTF_8));
  }

  @Test
  void serializeNumbers() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("a", Arrays.asList(-4, -4.5, new BigInteger("92233720368547758075"), Long.MAX_VALUE));

    assertEquals(
        "{\"a\":[-4,-4,-5,9223372036854775807]}",
        new String(JsonCanonicalizer.canonicalize(map), UTF_8));
  }
}
