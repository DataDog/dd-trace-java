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
 * Verifies that {@code findArtifactVersionInClasspath} relies only on {@code java.class.path} and
 * not on a URLClassLoader chain walk.
 *
 * <p>There are two independent reasons why a URLClassLoader walk cannot work here:
 *
 * <ol>
 *   <li><b>Agent thread constraint</b>: {@code AgentThreadFactory} explicitly sets the context
 *       classloader to {@code null} on every agent thread ({@code
 *       thread.setContextClassLoader(null)}), so {@code
 *       Thread.currentThread().getContextClassLoader()} always returns {@code null} on the
 *       telemetry thread where this method runs. Any URLClassLoader chain walk starting from {@code
 *       null} is a no-op.
 *   <li><b>Java 9+ system classloader</b>: On Java 9+, the system classloader is {@code
 *       jdk.internal.loader.ClassLoaders$AppClassLoader}, which does not extend {@link
 *       URLClassLoader}. Even if a non-null classloader were available, the system classloader
 *       would be missed by any {@code instanceof URLClassLoader} walk.
 * </ol>
 *
 * <p>The {@code java.class.path} system property covers standard deployments (JARs passed via
 * {@code -cp}). OSGi bundles and Spring Boot fat JAR nested libraries loaded via {@code
 * LaunchedURLClassLoader} are a known limitation: they are not listed in {@code java.class.path}
 * and cannot be reached from this method.
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
    ScaReachabilityTransformer transformer = new ScaReachabilityTransformer(db, null);

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
    ScaReachabilityTransformer transformer = new ScaReachabilityTransformer(db, null);

    String version = transformer.findArtifactVersionInClasspath("com.example:nonexistent-artifact");

    assertNull(version, "Unknown artifacts must return null");
  }

  /**
   * Validates that {@code findArtifactVersionInClasspath} works correctly when the context
   * classloader is {@code null} — which is the actual runtime condition on agent threads, because
   * {@code AgentThreadFactory} calls {@code thread.setContextClassLoader(null)} unconditionally.
   *
   * <p>If the implementation relied on a URLClassLoader chain walk starting from the context
   * classloader, it would silently return {@code null} here. The test asserts that {@code
   * java.class.path} scanning is the only mechanism in use and is sufficient.
   */
  @Test
  void findArtifactVersionInClasspath_worksWithNullContextClassLoader() throws Exception {
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(null);

      ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(JACKSON_JSON));
      ScaReachabilityTransformer transformer = new ScaReachabilityTransformer(db, null);

      String version =
          transformer.findArtifactVersionInClasspath("com.fasterxml.jackson.core:jackson-databind");

      assertNotNull(
          version,
          "jackson-databind must be found via java.class.path even with null context classloader. "
              + "java.class.path="
              + System.getProperty("java.class.path", ""));
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }
}
