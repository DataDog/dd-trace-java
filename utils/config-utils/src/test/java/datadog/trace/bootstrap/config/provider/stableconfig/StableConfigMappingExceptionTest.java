package datadog.trace.bootstrap.config.provider.stableconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class StableConfigMappingExceptionTest {

  @Test
  void constructorsWorkAsExpected() {
    StableConfigMappingException ex1 = new StableConfigMappingException("msg");
    StableConfigMappingException ex2 = new StableConfigMappingException("msg2");

    assertEquals("msg", ex1.getMessage());
    assertNull(ex1.getCause());
    assertEquals("msg2", ex2.getMessage());
  }

  @Test
  void safeToStringHandlesNull() {
    assertEquals("null", StableConfigMappingException.safeToString(null));
  }

  @Test
  void safeToStringHandlesShortString() {
    assertEquals("short string", StableConfigMappingException.safeToString("short string"));
  }

  @Test
  void safeToStringHandlesLongString() {
    char[] chars = new char[101];
    Arrays.fill(chars, 'a');
    String longStr = new String(chars);

    char[] halfChars = new char[50];
    Arrays.fill(halfChars, 'a');
    String half = new String(halfChars);

    assertEquals(
        half + "...(truncated)..." + half, StableConfigMappingException.safeToString(longStr));
  }
}
