package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.telemetry.dependency.Dependency;
import java.io.StringReader;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
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
          + "\"symbols\":[{\"class\":\"com/fasterxml/jackson/databind/ObjectMapper\",\"method\":\"readValue\"}]"
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

  // ---------------------------------------------------------------------------
  // matchVersion: artifact-ID-only fallback for JARs without pom.properties
  // ---------------------------------------------------------------------------

  @Test
  void matchVersion_exactMatchReturnsVersion() {
    Dependency dep = new Dependency("com.github.junrar:junrar", "7.5.5", "junrar-7.5.5.jar", null);
    assertEquals(
        "7.5.5",
        ScaReachabilityTransformer.matchVersion(
            "com.github.junrar:junrar", Collections.singletonList(dep)));
  }

  @Test
  void matchVersion_artifactIdOnlyFallbackForNoPomJar() {
    // Models guessFallbackNoPom result: no pom.properties in junrar-7.5.5.jar,
    // so DependencyResolver extracts only the artifact ID from the filename.
    Dependency dep = new Dependency("junrar", "7.5.5", "junrar-7.5.5.jar", null);
    assertEquals(
        "7.5.5",
        ScaReachabilityTransformer.matchVersion(
            "com.github.junrar:junrar", Collections.singletonList(dep)),
        "artifact-ID fallback must match 'junrar' against 'com.github.junrar:junrar'");
  }

  @Test
  void matchVersion_artifactIdFallbackDoesNotMatchWhenGroupIdPresent() {
    // If dep.name already contains ':' (from pom.properties), artifact-ID fallback must not fire:
    // "org.other:junrar" should NOT match "com.github.junrar:junrar".
    Dependency dep = new Dependency("org.other:junrar", "1.0.0", "junrar-1.0.0.jar", null);
    assertNull(
        ScaReachabilityTransformer.matchVersion(
            "com.github.junrar:junrar", Collections.singletonList(dep)),
        "artifact-ID fallback must not fire when dep.name already has a group ID");
  }

  @Test
  void matchVersion_emptyListReturnsNull() {
    assertNull(
        ScaReachabilityTransformer.matchVersion(
            "com.github.junrar:junrar", Collections.emptyList()));
  }

  @Test
  void matchVersion_exactMatchTakesPrecedenceOverFallback() {
    // Exact match must win even when an artifact-ID-only dep is also present.
    Dependency exact = new Dependency("com.github.junrar:junrar", "7.5.5", "a.jar", null);
    Dependency fallback = new Dependency("junrar", "1.0.0", "b.jar", null);
    assertEquals(
        "7.5.5",
        ScaReachabilityTransformer.matchVersion(
            "com.github.junrar:junrar", Arrays.asList(fallback, exact)));
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
