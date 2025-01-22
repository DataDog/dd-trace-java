package datadog.trace.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class RandomUtilsTest {
  private static final Pattern VERSION_4_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[8-9a-f][0-9a-f]{3}-[0-9a-f]{12}");

  @Test
  public void testRandomUUIDMatchesSpec() {
    for (int i = 0; i < 8; i++) {
      assertThat(RandomUtils.randomUUID().toString()).matches(VERSION_4_UUID);
    }
  }
}
