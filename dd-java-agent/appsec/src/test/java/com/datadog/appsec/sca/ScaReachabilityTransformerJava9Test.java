package com.datadog.appsec.sca;

import static com.datadog.appsec.sca.ScaBytecodeTestUtils.bytecodeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry;
import java.io.StringReader;
import java.lang.instrument.Instrumentation;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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

  @AfterEach
  void resetRegistry() {
    ScaReachabilityDependencyRegistry.INSTANCE.resetForTesting();
  }

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
   * Regression test for the snakeyaml deadlock (APPSEC-62260 follow-up):
   *
   * <p>JAR resolution (I/O via {@code resolveDependencies}) must happen on the telemetry thread
   * BEFORE {@code retransformClasses()} acquires JVM locks. If I/O runs inside the retransform
   * callback, libraries that trigger class loading during JAR resolution (e.g. snakeyaml) can
   * deadlock because the JVM class-loading lock and the retransform lock are both held.
   *
   * <p>With a mock {@code Instrumentation}, the retransform callback never fires, so {@code
   * resolveDependencies} (and therefore {@code jarCache}) can only be populated by the pre-warming
   * step that runs before {@code retransformClasses()}. The test asserts that {@code jarCache} is
   * non-empty after the call, which is only possible if the pre-warm loop ran.
   */
  @Test
  void performPendingRetransforms_prewarms_jarCache_before_retransformClasses() throws Exception {
    Instrumentation mockInstr = mock(Instrumentation.class);
    when(mockInstr.isModifiableClass(any())).thenReturn(true);
    when(mockInstr.getAllLoadedClasses()).thenReturn(new Class<?>[0]);

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(JACKSON_JSON));
    ScaReachabilityTransformer transformer = new ScaReachabilityTransformer(db, mockInstr);

    transformer.pendingRetransform.add(com.fasterxml.jackson.databind.ObjectMapper.class);
    transformer.performPendingRetransforms();

    assertFalse(
        transformer.jarCache.isEmpty(),
        "jarCache must be populated by the pre-warming step that runs before retransformClasses();"
            + " with a mock Instrumentation the transform callback never fires, so an empty jarCache"
            + " means the pre-warm loop was not executed");
  }

  /**
   * Regression test for the aggregator-artifact deadlock gap (Codex P1 on PR #11614):
   *
   * <p>When the entry's artifact is not in the class's own JAR (aggregator/starter case, e.g.,
   * {@code spring-boot-starter-web} whose watched classes live in {@code spring-context.jar}),
   * {@code resolveVersionForArtifact()} falls through to {@code findArtifactVersionInClasspath()},
   * which calls {@code resolveDependencies()} for every {@code java.class.path} entry — fresh JAR
   * I/O that would deadlock if it ran inside the retransform callback.
   *
   * <p>The pre-warm step must also populate {@code classpathArtifactCache} before {@code
   * retransformClasses()} acquires JVM locks. With a mock {@code Instrumentation} the callback
   * never fires, so the only way {@code classpathArtifactCache} can be populated is via the
   * pre-warm loop. {@code ObjectMapper} lives in {@code jackson-databind.jar}; the entry's artifact
   * is {@code jackson-core} (a different JAR that is a transitive test dependency), so {@code
   * matchVersion} fails on the class JAR and the classpath fallback must run during pre-warm.
   */
  @Test
  void performPendingRetransforms_prewarms_classpathArtifactCache_for_aggregator_artifacts()
      throws Exception {
    // Entry artifact is jackson-core, but ObjectMapper is in jackson-databind.jar.
    // matchVersion() returns null on classJarDeps → triggers findArtifactVersionInClasspath().
    String crossJarJson =
        "{\"version\":1,\"entries\":[{"
            + "\"vuln_id\":\"GHSA-test-cross-jar\","
            + "\"artifact\":\"com.fasterxml.jackson.core:jackson-core\","
            + "\"version_ranges\":[\"< 999.0.0\"],"
            + "\"symbols\":[{\"class\":\"com/fasterxml/jackson/databind/ObjectMapper\","
            + "\"method\":\"readValue\"}]}]}";

    Instrumentation mockInstr = mock(Instrumentation.class);
    when(mockInstr.isModifiableClass(any())).thenReturn(true);
    when(mockInstr.getAllLoadedClasses()).thenReturn(new Class<?>[0]);

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(crossJarJson));
    ScaReachabilityTransformer transformer = new ScaReachabilityTransformer(db, mockInstr);

    transformer.pendingRetransform.add(com.fasterxml.jackson.databind.ObjectMapper.class);
    transformer.performPendingRetransforms();

    // jackson-core is on the test classpath (transitive dependency of jackson-databind).
    // classpathArtifactCache must be populated during pre-warm — not by the callback (which never
    // fires with a mock Instrumentation). An empty cache means findArtifactVersionInClasspath()
    // would run under JVM retransform locks and risk the snakeyaml-style deadlock.
    assertNotNull(
        transformer.classpathArtifactCache.get("com.fasterxml.jackson.core:jackson-core"),
        "classpathArtifactCache must be populated during pre-warm for aggregator artifacts; "
            + "if empty, findArtifactVersionInClasspath() would run under JVM retransform locks");
  }

  @Test
  void transform_retransform_injectsCallbacksWhenVersionResolvedFromCache() throws Exception {
    String internalName =
        ScaReachabilityMethodLevelTest.TargetClass.class.getName().replace('.', '/');
    String json =
        "{\"version\":1,\"entries\":[{"
            + "\"vuln_id\":\"GHSA-transform\","
            + "\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 999.0.0\"],"
            + "\"symbols\":[{\"class\":\""
            + internalName
            + "\",\"method\":\"vulnerableMethod\"}]"
            + "}]}";
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(json));
    ScaReachabilityTransformer transformer = new ScaReachabilityTransformer(db, null);
    transformer.jarCache.put(
        ScaReachabilityMethodLevelTest.TargetClass.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .toURI(),
        Collections.singletonList(new Dependency("com.example:lib", "1.2.3", "test.jar", null)));

    byte[] transformed =
        transformer.transform(
            null,
            internalName,
            ScaReachabilityMethodLevelTest.TargetClass.class,
            ScaReachabilityMethodLevelTest.TargetClass.class.getProtectionDomain(),
            bytecodeOf(ScaReachabilityMethodLevelTest.TargetClass.class));

    assertNotNull(transformed);
    assertTrue(transformer.pendingRetransformNames.isEmpty());
    List<ScaReachabilityDependencyRegistry.DependencySnapshot> snapshots =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();
    assertEquals(1, snapshots.size());
    assertEquals("com.example:lib", snapshots.get(0).artifact);
    assertEquals("1.2.3", snapshots.get(0).version);
    assertEquals("GHSA-transform", snapshots.get(0).cves.get(0).vulnId);
    assertNull(snapshots.get(0).cves.get(0).hit);
  }

  @Test
  void checkAlreadyLoadedClassesSchedulesOnlyMatchingNonBootstrapClasses() throws Exception {
    String internalName =
        ScaReachabilityMethodLevelTest.TargetClass.class.getName().replace('.', '/');
    String json =
        "{\"version\":1,\"entries\":["
            + "{\"vuln_id\":\"GHSA-target\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 999.0.0\"],"
            + "\"symbols\":[{\"class\":\""
            + internalName
            + "\",\"method\":\"vulnerableMethod\"}]},"
            + "{\"vuln_id\":\"GHSA-jdk\",\"artifact\":\"com.example:jdk\","
            + "\"version_ranges\":[\"< 999.0.0\"],"
            + "\"symbols\":[{\"class\":\"java/lang/String\",\"method\":\"substring\"}]}"
            + "]}";

    Instrumentation mockInstr = mock(Instrumentation.class);
    when(mockInstr.getAllLoadedClasses())
        .thenReturn(
            new Class<?>[] {
              null, String[].class, String.class, ScaReachabilityMethodLevelTest.TargetClass.class,
            });
    ScaReachabilityTransformer transformer =
        new ScaReachabilityTransformer(ScaCveDatabase.parse(new StringReader(json)), mockInstr);

    transformer.checkAlreadyLoadedClasses();

    assertEquals(1, transformer.pendingRetransform.size());
    assertSame(
        ScaReachabilityMethodLevelTest.TargetClass.class, transformer.pendingRetransform.peek());
  }

  @Test
  void performPendingRetransforms_noopsWithoutInstrumentation() throws Exception {
    ScaReachabilityTransformer transformer =
        new ScaReachabilityTransformer(
            ScaCveDatabase.parse(new StringReader("{\"version\":1,\"entries\":[]}")), null);
    transformer.pendingRetransform.add(ScaReachabilityMethodLevelTest.TargetClass.class);

    transformer.performPendingRetransforms();

    assertSame(
        ScaReachabilityMethodLevelTest.TargetClass.class, transformer.pendingRetransform.peek());
  }

  @Test
  void resolveArtifactDep_returnsCachedClasspathArtifact() throws Exception {
    ScaReachabilityTransformer transformer =
        new ScaReachabilityTransformer(ScaCveDatabase.parse(new StringReader(JACKSON_JSON)), null);
    Dependency cached = new Dependency("com.example:lib", "1.0.0", "lib.jar", null);
    transformer.classpathArtifactCache.put("com.example:lib", cached);

    assertSame(cached, transformer.resolveArtifactDep("com.example:lib", Collections.emptyList()));
  }

  @Test
  void matchVersion_nullDepNameDoesNotThrow() {
    // guessFallbackNoPom can produce Dependency(name=null, ...) for JARs with unrecognizable names.
    Dependency nullName = new Dependency(null, "1.0", "foo.jar", null);
    assertNull(
        ScaReachabilityTransformer.matchVersion(
            "com.example:foo", Collections.singletonList(nullName)));
  }

  /**
   * Regression test for the registry key mismatch bug (PR #11614).
   *
   * <p>For JARs without {@code pom.properties}, {@code DependencyResolver.guessFallbackNoPom}
   * produces a dep with {@code name = "junrar"} (artifactId only, no groupId). {@code
   * resolveArtifactDep} must return that dep object so that callers ({@code processClass}) can use
   * {@code dep.name} — not {@code entry.artifact()} — when calling {@code registerCve} and building
   * {@code MethodCallbackSpec}. Using {@code entry.artifact()} would create a registry key ({@code
   * "com.github.junrar:junrar@7.5.5"}) that mismatches the key {@code DependencyService} will use
   * ({@code "junrar@7.5.5"}), causing the CVE telemetry to lose source/hash or appear under the
   * wrong name.
   */
  @Test
  void resolveArtifactDep_noPomJar_returnsArtifactIdOnlyName() throws Exception {
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(JACKSON_JSON));
    ScaReachabilityTransformer transformer = new ScaReachabilityTransformer(db, null);

    // Simulate guessFallbackNoPom: dep.name is artifactId only (no groupId).
    Dependency noPomDep =
        new Dependency("jackson-databind", "2.9.0", "jackson-databind-2.9.0.jar", null);

    Dependency resolved =
        transformer.resolveArtifactDep(
            "com.fasterxml.jackson.core:jackson-databind", Collections.singletonList(noPomDep));

    assertNotNull(resolved, "should resolve via artifactId-only fallback");
    assertEquals(
        "jackson-databind",
        resolved.name,
        "resolved dep.name must be the artifactId-only name from the jar, "
            + "not entry.artifact() — so registerCve uses the same key as DependencyService");
    assertEquals("2.9.0", resolved.version);
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
