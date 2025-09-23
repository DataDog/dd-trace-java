package datadog.environment;

import static java.lang.Integer.MAX_VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.of;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JavaVersionTest {
  static Stream<Arguments> argumentsParsing() {
    return Stream.of(
        of("", 0, 0, 0),
        of("a.0.0", 0, 0, 0),
        of("0.a.0", 0, 0, 0),
        of("0.0.a", 0, 0, 0),
        of("1.a.0_0", 0, 0, 0),
        of("1.8.a_0", 0, 0, 0),
        of("1.8.0_a", 0, 0, 0),
        of("1.7", 7, 0, 0),
        of("1.7.0", 7, 0, 0),
        of("1.7.0_221", 7, 0, 221),
        of("1.8", 8, 0, 0),
        of("1.8.0", 8, 0, 0),
        of("1.8.0_212", 8, 0, 212),
        of("1.8.0_292", 8, 0, 292),
        of("9-ea", 9, 0, 0),
        of("9.0.4", 9, 0, 4),
        of("9.1.2", 9, 1, 2),
        of("10.0.2", 10, 0, 2),
        of("11", 11, 0, 0),
        of("11.0.6", 11, 0, 6),
        of("11.0.11", 11, 0, 11),
        of("12.0.2", 12, 0, 2),
        of("13.0.2", 13, 0, 2),
        of("14", 14, 0, 0),
        of("14.0.2", 14, 0, 2),
        of("15", 15, 0, 0),
        of("15.0.2", 15, 0, 2),
        of("16.0.1", 16, 0, 1),
        of("11.0.9.1+1", 11, 0, 9),
        of("11.0.6+10", 11, 0, 6),
        of("17.0.4-x", 17, 0, 4));
  }

  @ParameterizedTest(name = "[{index}] {0} parsed as {1}.{2}.{3}")
  @MethodSource("argumentsParsing")
  void testParsing(String version, int major, int minor, int update) {
    JavaVersion javaVersion = JavaVersion.parseJavaVersion(version);

    assertEquals(major, javaVersion.major);
    assertEquals(minor, javaVersion.minor);
    assertEquals(update, javaVersion.update);

    assertFalse(javaVersion.is(major - 1));
    assertTrue(javaVersion.is(major));
    assertFalse(javaVersion.is(major + 1));

    assertFalse(javaVersion.is(major, minor - 1));
    assertTrue(javaVersion.is(major, minor));
    assertFalse(javaVersion.is(major, minor + 1));

    assertFalse(javaVersion.is(major, minor, update - 1));
    assertTrue(javaVersion.is(major, minor, update));
    assertFalse(javaVersion.is(major, minor, update + 1));

    assertTrue(javaVersion.isAtLeast(major, minor, update));

    assertFalse(javaVersion.isBetween(major, minor, update, major, minor, update));

    assertTrue(javaVersion.isBetween(major, minor, update, MAX_VALUE, MAX_VALUE, MAX_VALUE));
    assertTrue(javaVersion.isBetween(major, minor, update, major + 1, 0, 0));
    assertTrue(javaVersion.isBetween(major, minor, update, major, minor + 1, 0));
    assertTrue(javaVersion.isBetween(major, minor, update, major, minor, update + 1));

    assertFalse(javaVersion.isBetween(major, minor, update, major - 1, 0, 0));
    assertFalse(javaVersion.isBetween(major, minor, update, major, minor - 1, 0));
    assertFalse(javaVersion.isBetween(major, minor, update, major, minor, update - 1));
  }

  static Stream<Arguments> argumentsAtLeast() {
    return Stream.of(
        of("17.0.5+8", 17, 0, 5),
        of("17.0.5", 17, 0, 5),
        of("17.0.6+8", 17, 0, 5),
        of("11.0.17+8", 11, 0, 17),
        of("11.0.18+8", 11, 0, 17),
        of("11.0.17", 11, 0, 17),
        of("1.8.0_352", 8, 0, 352),
        of("1.8.0_362", 8, 0, 352));
  }

  @ParameterizedTest(name = "[{index}] {0} is at least {1}.{2}.{3}")
  @MethodSource("argumentsAtLeast")
  void testIsAtLeast(String version, int major, int minor, int update) {
    JavaVersion javaVersion = JavaVersion.parseJavaVersion(version);
    assertTrue(javaVersion.isAtLeast(major, minor, update));
  }
}
