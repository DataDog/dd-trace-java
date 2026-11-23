package datadog.context.propagation;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HeaderSchemaTest {

  @Test
  void namesAreUniqueAndLowerCase() {
    Set<String> names = new HashSet<>();
    for (HeaderSchema header : HeaderSchema.values()) {
      assertEquals(header.headerName.toLowerCase(Locale.ROOT), header.headerName);
      assertTrue(names.add(header.headerName), "duplicate " + header.headerName);
    }
  }

  @Test
  void encodingsMatchTheName() {
    for (HeaderSchema header : HeaderSchema.values()) {
      byte[] ascii = header.headerName.getBytes(US_ASCII);
      assertArrayEquals(ascii, header.asciiName());
      assertEquals(ascii.length, header.length());
      for (int i = 0; i < ascii.length; i++) {
        assertEquals(ascii[i], header.asciiByte(i));
      }
    }
  }

  @Test
  void asciiNameIsACopy() {
    byte[] copy = HeaderSchema.TRACEPARENT.asciiName();
    copy[0] = 'X';
    assertEquals('t', HeaderSchema.TRACEPARENT.asciiByte(0));
  }

  @Test
  void ordinalsFitInAByte() {
    // CarrierCache stores ordinal() + 1 in a byte
    assertTrue(HeaderSchema.count() < Byte.MAX_VALUE);
  }

  @Test
  void onlyDatadogIdHeadersAreParseOnly() {
    Set<HeaderSchema> parseOnly = new HashSet<>();
    for (HeaderSchema header : HeaderSchema.values()) {
      if (header.isParseOnly()) {
        parseOnly.add(header);
      }
    }
    assertEquals(
        new HashSet<>(
            java.util.Arrays.asList(
                HeaderSchema.X_DATADOG_TRACE_ID,
                HeaderSchema.X_DATADOG_PARENT_ID,
                HeaderSchema.X_DATADOG_SAMPLING_PRIORITY)),
        parseOnly);
  }

  @Test
  void lookupsByNameAndOrdinal() {
    assertEquals(HeaderSchema.values().length, HeaderSchema.count());
    for (HeaderSchema header : HeaderSchema.values()) {
      assertSame(header, HeaderSchema.forName(header.headerName));
      assertSame(header, HeaderSchema.fromOrdinal(header.ordinal()));
    }
    assertNull(HeaderSchema.forName("x-custom-header"));
    assertNull(HeaderSchema.forName("Traceparent"));
  }

  @Test
  void readByEveryStyleIsForwardingUserAgentAndClientIp() {
    Set<HeaderSchema> everyStyle = new HashSet<>();
    for (HeaderSchema header : HeaderSchema.values()) {
      if (header.isReadByEveryStyle()) {
        everyStyle.add(header);
      }
    }
    Set<HeaderSchema> expected = new HashSet<>();
    for (HeaderSchema header :
        new HeaderSchema[] {
          HeaderSchema.FORWARDED,
          HeaderSchema.FORWARDED_FOR,
          HeaderSchema.X_FORWARDED_PROTO,
          HeaderSchema.X_FORWARDED_HOST,
          HeaderSchema.X_FORWARDED_FOR,
          HeaderSchema.X_FORWARDED_PORT,
          HeaderSchema.USER_AGENT,
          HeaderSchema.X_CLIENT_IP,
          HeaderSchema.TRUE_CLIENT_IP,
          HeaderSchema.X_CLUSTER_CLIENT_IP,
          HeaderSchema.X_REAL_IP,
          HeaderSchema.FASTLY_CLIENT_IP,
          HeaderSchema.CF_CONNECTING_IP,
          HeaderSchema.CF_CONNECTING_IP_V6
        }) {
      expected.add(header);
    }
    assertEquals(expected, everyStyle);
  }
}
