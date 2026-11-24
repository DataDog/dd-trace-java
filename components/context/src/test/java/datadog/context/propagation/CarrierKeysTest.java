package datadog.context.propagation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class CarrierKeysTest {

  private static final CarrierKeys KEYS =
      CarrierKeys.builder()
          .name("x-datadog-trace-id")
          .name("traceparent")
          .name("X-B3-TraceId")
          .prefix("ot-baggage-")
          .build();

  /** {@code key}'s bytes as the slice between a buffer's position and limit, inside other bytes. */
  private static ByteBuffer bytes(String key) {
    byte[] key8 = key.getBytes(UTF_8);
    byte[] around = new byte[3 + key8.length + 2];
    System.arraycopy(key8, 0, around, 3, key8.length);
    ByteBuffer buffer = ByteBuffer.wrap(around);
    buffer.position(3).limit(3 + key8.length);
    return buffer;
  }

  private static void assertContains(boolean expected, String key) {
    assertEquals(expected, KEYS.contains(key), key);
    ByteBuffer buffer = bytes(key);
    assertEquals(expected, KEYS.containsAscii(buffer), key + " as bytes");
    assertEquals(3, buffer.position(), "position moved");
  }

  @Test
  void matchesNamesIgnoringCase() {
    assertContains(true, "x-datadog-trace-id");
    assertContains(true, "X-Datadog-Trace-Id");
    assertContains(true, "traceparent");
    assertContains(true, "x-b3-traceid");
    assertContains(true, "X-B3-TRACEID");
  }

  @Test
  void matchesPrefixes() {
    assertContains(true, "ot-baggage-user");
    assertContains(true, "OT-Baggage-user");
    // the prefix alone is a key starting with it
    assertContains(true, "ot-baggage-");
    assertContains(false, "ot-baggage");
  }

  @Test
  void rejectsOtherKeys() {
    assertContains(false, "content-type");
    assertContains(false, "traceparen");
    assertContains(false, "traceparentx");
    assertContains(false, "");
    // longer than any name, and not a prefix match
    assertContains(false, "x-a-key-longer-than-every-declared-name");
    // as long as a declared name, in lower and upper case: the exact pass misses, and only the
    // upper-case key is compared ignoring case, which misses too
    assertContains(false, "traceparenu");
    assertContains(false, "TRACEPARENU");
  }

  @Test
  void headerMatchesIgnoringCaseOnlyWhenTheKeyHasUpperCase() {
    CarrierKeys keys = CarrierKeys.of(HeaderSchema.TRACEPARENT);
    assertSame(HeaderSchema.TRACEPARENT, keys.header("traceparent"));
    assertSame(HeaderSchema.TRACEPARENT, keys.header("TraceParent"));
    assertSame(HeaderSchema.TRACEPARENT, keys.header("TRACEPARENT"));
    assertNull(keys.header("traceparenu"));
    assertNull(keys.header("TRACEPARENU"));
  }

  @Test
  void nameAsciiReturnsTheDeclaredName() {
    assertEquals("x-b3-traceid", KEYS.nameAscii(bytes("X-B3-TraceId")));
    assertEquals("traceparent", KEYS.nameAscii(bytes("TRACEPARENT")));
    // prefixes aren't names
    assertNull(KEYS.nameAscii(bytes("ot-baggage-user")));
    assertNull(KEYS.nameAscii(bytes("content-type")));
    assertTrue(KEYS.hasPrefixAscii(bytes("OT-Baggage-user")));
    assertFalse(KEYS.hasPrefixAscii(bytes("traceparent")));
    ByteBuffer buffer = bytes("traceparent");
    KEYS.nameAscii(buffer);
    KEYS.hasPrefixAscii(buffer);
    assertEquals(3, buffer.position(), "position moved");
  }

  @Test
  void knownHeadersMatchAsTheirConstant() {
    CarrierKeys keys =
        CarrierKeys.builder()
            .header(HeaderSchema.X_DATADOG_PARENT_ID)
            .name("TraceParent")
            .name("x-custom-header")
            .prefix("ot-baggage-")
            .build();
    // declared as a constant, or by a name that is a known header, either way
    assertSame(HeaderSchema.X_DATADOG_PARENT_ID, keys.header("X-Datadog-Parent-Id"));
    assertSame(HeaderSchema.X_DATADOG_PARENT_ID, keys.headerAscii(bytes("x-datadog-parent-id")));
    assertSame(HeaderSchema.TRACEPARENT, keys.header("traceparent"));
    assertSame(HeaderSchema.TRACEPARENT, keys.headerAscii(bytes("TRACEPARENT")));
    // a declared name with no constant, a prefix match, or an undeclared known header: no constant
    assertNull(keys.header("x-custom-header"));
    assertNull(keys.headerAscii(bytes("x-custom-header")));
    assertTrue(keys.contains("x-custom-header"));
    assertNull(keys.header("ot-baggage-user"));
    assertNull(keys.header("tracestate"));
    assertNull(keys.headerAscii(bytes("tracestate")));
    assertSame(HeaderSchema.BAGGAGE, CarrierKeys.of(HeaderSchema.BAGGAGE).header("baggage"));
    assertNull(CarrierKeys.all().header("baggage"));
    assertNull(CarrierKeys.none().headerAscii(bytes("baggage")));
  }

  @Test
  void unionKeepsConstants() {
    CarrierKeys union =
        CarrierKeys.of(HeaderSchema.TRACEPARENT).union(CarrierKeys.of("x-custom-header"));
    assertSame(HeaderSchema.TRACEPARENT, union.header("traceparent"));
    assertNull(union.header("x-custom-header"));
    assertTrue(union.contains("x-custom-header"));
  }

  @Test
  void nonAsciiBytesMatchNothing() {
    assertFalse(KEYS.containsAscii(bytes("traceparént")));
    assertFalse(KEYS.containsAscii(ByteBuffer.wrap(new byte[] {(byte) 0xC3, (byte) 0xA9})));
  }

  @Test
  void allAndNone() {
    assertTrue(CarrierKeys.all().isAll());
    assertTrue(CarrierKeys.all().contains("anything"));
    assertTrue(CarrierKeys.all().containsAscii(bytes("anything")));
    assertFalse(CarrierKeys.none().isAll());
    assertFalse(CarrierKeys.none().contains("traceparent"));
    assertFalse(CarrierKeys.none().containsAscii(bytes("traceparent")));
    assertFalse(KEYS.isAll());
  }

  @Test
  void unionKeepsBothSides() {
    CarrierKeys other = CarrierKeys.builder().name("baggage").prefix("x-dd-proxy").build();
    CarrierKeys union = KEYS.union(other);
    assertTrue(union.contains("traceparent"));
    assertTrue(union.contains("ot-baggage-x"));
    assertTrue(union.contains("Baggage"));
    assertTrue(union.contains("x-dd-proxy-name"));
    assertFalse(union.contains("content-type"));
  }

  @Test
  void unionWithAllOrNone() {
    assertSame(CarrierKeys.all(), KEYS.union(CarrierKeys.all()));
    assertSame(CarrierKeys.all(), CarrierKeys.all().union(KEYS));
    assertSame(KEYS, KEYS.union(CarrierKeys.none()));
    assertSame(KEYS, CarrierKeys.none().union(KEYS));
  }

  @Test
  void builderIgnoresNullAndEmptyAndBuildsSingletons() {
    assertSame(
        CarrierKeys.none(),
        CarrierKeys.builder().name(null).name("").prefix(null).prefix("").build());
    assertSame(
        CarrierKeys.all(), CarrierKeys.builder().name("a").addAll(CarrierKeys.all()).build());
    CarrierKeys copied = CarrierKeys.builder().addAll(KEYS).build();
    assertTrue(copied.contains("X-B3-TraceId"));
    assertTrue(copied.contains("ot-baggage-y"));
  }

  @Test
  void prefixOnlyKeys() {
    CarrierKeys prefixes = CarrierKeys.builder().prefix("x-dd-proxy").build();
    assertTrue(prefixes.contains("x-dd-proxy-request-time-ms"));
    assertTrue(prefixes.containsAscii(bytes("X-DD-PROXY")));
    assertFalse(prefixes.contains("x-dd"));
  }

  @Test
  void describesItself() {
    assertEquals("CarrierKeys{all}", CarrierKeys.all().toString());
    assertEquals(
        "CarrierKeys{names=[traceparent, x-b3-traceid, x-datadog-trace-id], prefixes=[ot-baggage-]}",
        KEYS.toString());
  }
}
