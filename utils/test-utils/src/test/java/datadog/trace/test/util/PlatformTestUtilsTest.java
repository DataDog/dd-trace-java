package datadog.trace.test.util;

import static datadog.trace.test.util.PlatformTestUtils.normalizeExecutableName;
import static datadog.trace.test.util.PlatformTestUtils.normalizeLineEndings;
import static datadog.trace.test.util.PlatformTestUtils.normalizeLocalhostHostname;
import static datadog.trace.test.util.PlatformTestUtils.normalizeLocalhostUrl;
import static datadog.trace.test.util.PlatformTestUtils.normalizePathSeparators;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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

  @Test
  void removesWindowsExecutableSuffixOnlyOnWindows() {
    String windowsName = "java.exe";

    assertEquals("java", PlatformTestUtils.normalizeExecutableName(windowsName, true));
    assertEquals("java", PlatformTestUtils.normalizeExecutableName("java.EXE", true));
    assertEquals("java", PlatformTestUtils.normalizeExecutableName("java", true));
    assertEquals("javac", PlatformTestUtils.normalizeExecutableName("javac", true));
    assertNull(PlatformTestUtils.normalizeExecutableName(null, true));
    assertSame(windowsName, PlatformTestUtils.normalizeExecutableName(windowsName, false));
    assertEquals(
        OperatingSystem.isWindows() ? "java" : windowsName, normalizeExecutableName(windowsName));
  }

  @Test
  void normalizesExactWindowsLocalhostHostnameOnlyOnWindows() {
    String value = "127.0.0.1";

    assertEquals("localhost", PlatformTestUtils.normalizeLocalhostHostname(value, true));
    assertEquals("127.0.0.10", PlatformTestUtils.normalizeLocalhostHostname("127.0.0.10", true));
    assertSame(value, PlatformTestUtils.normalizeLocalhostHostname(value, false));
    assertEquals(
        OperatingSystem.isWindows() ? "localhost" : value, normalizeLocalhostHostname(value));
  }

  @Test
  void normalizesOnlyTheWindowsUrlHost() {
    String value = "http://127.0.0.1:8080/a/127.0.0.1?q=127.0.0.1#127.0.0.1";
    String normalized = "http://localhost:8080/a/127.0.0.1?q=127.0.0.1#127.0.0.1";

    assertEquals(normalized, PlatformTestUtils.normalizeLocalhostUrl(value, true));
    assertSame(value, PlatformTestUtils.normalizeLocalhostUrl(value, false));
    assertEquals(OperatingSystem.isWindows() ? normalized : value, normalizeLocalhostUrl(value));
    assertEquals(
        "http://127.0.0.10/test",
        PlatformTestUtils.normalizeLocalhostUrl("http://127.0.0.10/test", true));
    assertEquals("not a url", PlatformTestUtils.normalizeLocalhostUrl("not a url", true));
    assertNull(PlatformTestUtils.normalizeLocalhostUrl(null, true));
    assertEquals(
        "http://user:password@localhost:8080/test",
        PlatformTestUtils.normalizeLocalhostUrl("http://user:password@127.0.0.1:8080/test", true));
  }
}
