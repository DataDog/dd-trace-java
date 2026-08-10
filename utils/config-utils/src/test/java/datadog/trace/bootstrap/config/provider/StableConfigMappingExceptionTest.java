package datadog.trace.bootstrap.config.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.config.provider.stableconfig.StableConfigMappingException;
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
    StableConfigMappingException ex =
        assertThrows(
            StableConfigMappingException.class,
            () -> StableConfigMappingException.throwStableConfigMappingException("msg", null));
    assertTrue(ex.getMessage().endsWith(" null"));
  }

  @Test
  void safeToStringHandlesShortString() {
    StableConfigMappingException ex =
        assertThrows(
            StableConfigMappingException.class,
            () ->
                StableConfigMappingException.throwStableConfigMappingException(
                    "msg", "short string"));
    assertTrue(ex.getMessage().endsWith(" short string"));
  }

  @Test
  void safeToStringHandlesLongString() {
    char[] chars = new char[101];
    Arrays.fill(chars, 'a');
    String longStr = new String(chars);

    StableConfigMappingException ex =
        assertThrows(
            StableConfigMappingException.class,
            () -> StableConfigMappingException.throwStableConfigMappingException("msg", longStr));

    char[] halfChars = new char[50];
    Arrays.fill(halfChars, 'a');
    String half = new String(halfChars);
    assertTrue(ex.getMessage().endsWith(" " + half + "...(truncated)..." + half));
  }
}
