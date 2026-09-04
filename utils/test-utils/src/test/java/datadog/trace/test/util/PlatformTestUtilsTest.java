package datadog.trace.test.util;

import static datadog.trace.test.util.PlatformTestUtils.normalizeLineEndings;
import static datadog.trace.test.util.PlatformTestUtils.normalizePathSeparators;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.environment.OperatingSystem;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformTestUtilsTest {
  @Test
  void convertsWindowsLineEndingsOnlyOnWindows() {
    String value = "first\r\nsecond";

    assertEquals("first\nsecond", PlatformTestUtils.normalizeLineEndings(value, true));
    assertSame(value, PlatformTestUtils.normalizeLineEndings(value, false));
    assertEquals(
        OperatingSystem.isWindows() ? "first\nsecond" : value, normalizeLineEndings(value));
  }

  @Test
  void convertsWindowsPathSeparatorsOnlyOnWindows() {
    String value = "directory\\file";

    assertEquals("directory/file", PlatformTestUtils.normalizePathSeparators(value, true));
    assertSame(value, PlatformTestUtils.normalizePathSeparators(value, false));
    assertEquals(
        OperatingSystem.isWindows() ? "directory/file" : value, normalizePathSeparators(value));
  }

  @Test
  void convertsCollectionsWithoutMutatingTheInput() {
    List<String> values = Arrays.asList("directory\\file", "another\\file");
    Collection<String> normalizedValues = PlatformTestUtils.normalizePathSeparators(values, true);

    assertNotSame(values, normalizedValues);
    assertEquals(Arrays.asList("directory/file", "another/file"), normalizedValues);
    assertEquals(Arrays.asList("directory\\file", "another\\file"), values);
    assertSame(values, PlatformTestUtils.normalizePathSeparators(values, false));
    assertEquals(
        OperatingSystem.isWindows() ? Arrays.asList("directory/file", "another/file") : values,
        normalizePathSeparators(values));
  }
}
