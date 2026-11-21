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
  void lookupsByNameAndOrdinal() {
    assertEquals(HeaderSchema.values().length, HeaderSchema.count());
    for (HeaderSchema header : HeaderSchema.values()) {
      assertSame(header, HeaderSchema.forName(header.headerName));
      assertSame(header, HeaderSchema.fromOrdinal(header.ordinal()));
    }
    assertNull(HeaderSchema.forName("x-custom-header"));
    assertNull(HeaderSchema.forName("Traceparent"));
  }
}
