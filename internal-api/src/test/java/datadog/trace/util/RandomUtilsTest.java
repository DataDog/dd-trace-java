package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class RandomUtilsTest {
  private static final Pattern VERSION_4_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[8-9a-f][0-9a-f]{3}-[0-9a-f]{12}");

  @Test
  public void testRandomUUIDMatchesSpec() {
    for (int i = 0; i < 8; i++) {
      assertTrue(VERSION_4_UUID.matcher(RandomUtils.randomUUID().toString()).matches());
    }
  }

  @Test
  public void testSecureRandomUUIDMatchesSpec() {
    for (int i = 0; i < 8; i++) {
      assertTrue(VERSION_4_UUID.matcher(RandomUtils.secureRandomUUID().toString()).matches());
    }
  }
}
