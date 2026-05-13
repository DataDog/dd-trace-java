package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.StringReader;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

/**
 * Verifies that Path B classpath scanning works on Java 9+, where the system classloader
 * (jdk.internal.loader.ClassLoaders$AppClassLoader) no longer extends {@link URLClassLoader}.
 *
 * <p>The change was introduced in Java 9 (Project Jigsaw) and is permanent in all subsequent JDK
 * versions (11, 17, 21, …). Without the {@code java.class.path} fallback, Path B would silently
 * fail to find vulnerable artifacts on any modern JDK.
 */
class ScaReachabilityTransformerJava9Test {

  private static final String JACKSON_JSON =
      "{\"version\":1,\"entries\":[{"
          + "\"vuln_id\":\"GHSA-test-jackson\","
          + "\"artifact\":\"com.fasterxml.jackson.core:jackson-databind\","
          + "\"version_ranges\":[\"< 999.0.0\"],"
          + "\"symbols\":[{\"class\":\"com/fasterxml/jackson/databind/ObjectMapper\",\"method\":null}]"
          + "}]}";

  @Test
  @EnabledForJreRange(min = JRE.JAVA_9)
  void systemClassLoaderIsNotUrlClassLoaderOnJava9Plus() {
    // This is the root cause of the bug: the URLClassLoader chain walk misses the system
    // classloader on Java 9+. Verify our assumption so the test has a clear failure message
    // if the JDK ever reverts this (extremely unlikely).
    assertFalse(
        ClassLoader.getSystemClassLoader() instanceof URLClassLoader,
        "On Java 9+, the system classloader must not be a URLClassLoader — "
            + "this is the invariant that makes the java.class.path fallback necessary");
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_9)
  void findArtifactVersionInClasspath_findsArtifactViaJavaClassPathOnJava9Plus() throws Exception {
    // jackson-databind is on the test classpath (testImplementation dependency).
    // On Java 9+, it would NOT be found by the URLClassLoader chain because the system
    // classloader is not a URLClassLoader. The java.class.path fallback must find it.
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(JACKSON_JSON));
    ScaReachabilityTransformer transformer = new ScaReachabilityTransformer(db);

    String version =
        transformer.findArtifactVersionInClasspath("com.fasterxml.jackson.core:jackson-databind");

    assertNotNull(
        version,
        "jackson-databind must be found via java.class.path fallback on Java 9+. "
            + "java.class.path="
            + System.getProperty("java.class.path", ""));
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_9)
  void findArtifactVersionInClasspath_returnsNullForUnknownArtifact() throws Exception {
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(JACKSON_JSON));
    ScaReachabilityTransformer transformer = new ScaReachabilityTransformer(db);

    String version = transformer.findArtifactVersionInClasspath("com.example:nonexistent-artifact");

    assertNull(version, "Unknown artifacts must return null");
  }
}
