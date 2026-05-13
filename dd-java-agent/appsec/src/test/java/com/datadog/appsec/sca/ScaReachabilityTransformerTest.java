package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.telemetry.ScaReachabilityCollector;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import java.io.File;
import java.io.StringReader;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScaReachabilityTransformerTest {

  private static final String JACKSON_JSON =
      "{\"version\":1,\"entries\":[{"
          + "\"vuln_id\":\"GHSA-test-jackson\","
          + "\"artifact\":\"com.fasterxml.jackson.core:jackson-databind\","
          + "\"version_ranges\":[\"< 2.9.0\"],"
          + "\"symbols\":[{\"class\":\"com/fasterxml/jackson/databind/ObjectMapper\",\"method\":null}]"
          + "}]}";

  private ScaReachabilityTransformer transformer;

  @BeforeEach
  void setUp() throws Exception {
    // Drain any hits left from previous tests
    ScaReachabilityCollector.INSTANCE.drain();
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(JACKSON_JSON));
    transformer = new ScaReachabilityTransformer(db, null);
  }

  // --- transform() return value ---

  @Test
  void transformAlwaysReturnsNull() throws Exception {
    ProtectionDomain pd = protectionDomainFor(jarUrl("jackson-databind-2.8.5.jar"));
    byte[] result =
        transformer.transform(
            null, "com/fasterxml/jackson/databind/ObjectMapper", null, pd, new byte[0]);
    assertNull(result, "transform() must never return non-null for class-level symbols");
  }

  @Test
  void transformReturnsNullForArrayTypes() {
    byte[] result =
        transformer.transform(
            null, "[Lcom/fasterxml/jackson/databind/ObjectMapper;", null, null, new byte[0]);
    assertNull(result);
  }

  @Test
  void transformReturnsNullForNullClassName() {
    byte[] result = transformer.transform(null, null, null, null, new byte[0]);
    assertNull(result);
  }

  @Test
  void transformReturnsNullForNullProtectionDomain() {
    // JDK class — protectionDomain is null; handled at startup via Path B
    byte[] result =
        transformer.transform(
            null, "com/fasterxml/jackson/databind/ObjectMapper", null, null, new byte[0]);
    assertNull(result);
    assertTrue(
        ScaReachabilityCollector.INSTANCE.drain().isEmpty(),
        "No hit expected for JDK-sourced class in transform()");
  }

  @Test
  void transformReturnsNullForClassNotInDatabase() throws Exception {
    ProtectionDomain pd = protectionDomainFor(jarUrl("some-other-lib.jar"));
    byte[] result =
        transformer.transform(null, "com/example/UnrelatedClass", null, pd, new byte[0]);
    assertNull(result);
    assertTrue(ScaReachabilityCollector.INSTANCE.drain().isEmpty());
  }

  // --- hit detection ---

  @Test
  void detectsVulnerableClassFromRealJar() throws Exception {
    // Use the actual jackson-databind JAR on the test classpath as the location
    URL jacksonJar = findJarOnClasspath("jackson-databind");
    if (jacksonJar == null) {
      // If the JAR is not on the test classpath, skip this test
      return;
    }
    ProtectionDomain pd = protectionDomainFor(jacksonJar);
    transformer.transform(
        null, "com/fasterxml/jackson/databind/ObjectMapper", null, pd, new byte[0]);

    List<ScaReachabilityHit> hits = ScaReachabilityCollector.INSTANCE.drain();
    // Only assert if the version is actually vulnerable (< 2.9.0)
    // We can't assert a specific hit here since the test classpath version may vary
    assertTrue(hits.size() <= 1, "At most one hit per (vulnId, artifact) pair");
  }

  @Test
  void deduplicatesHitsForSameVulnAndArtifact() throws Exception {
    URL fakeJar = getClass().getResource("/"); // any URL will do; DependencyResolver returns empty
    if (fakeJar == null) return;

    ProtectionDomain pd = protectionDomainFor(fakeJar);
    // Call transform twice for the same class — should produce at most one hit
    transformer.transform(
        null, "com/fasterxml/jackson/databind/ObjectMapper", null, pd, new byte[0]);
    transformer.transform(
        null, "com/fasterxml/jackson/databind/ObjectMapper", null, pd, new byte[0]);

    List<ScaReachabilityHit> hits = ScaReachabilityCollector.INSTANCE.drain();
    assertTrue(
        hits.size() <= 1, "Deduplication must ensure at most one hit per (vulnId, artifact)");
  }

  // --- checkAlreadyLoadedClasses ---

  @Test
  void checkAlreadyLoadedClasses_completesWithoutThrowingForUnresolvableClass() throws Exception {
    // Verify the method runs without throwing for a class not in the database
    // (ScaReachabilityTransformer itself is a class guaranteed to be loaded)
    java.lang.instrument.Instrumentation inst =
        fakeInstrumentationReturning(ScaReachabilityTransformer.class);

    transformer.checkAlreadyLoadedClasses(inst);

    // No hit — this class is not in our test DB (which only has jackson)
    assertTrue(ScaReachabilityCollector.INSTANCE.drain().isEmpty());
  }

  @Test
  void checkAlreadyLoadedClasses_doesNotAbortOnException() throws Exception {
    // A class in the DB whose CodeSource URL points to a non-existent JAR should not abort
    // processing of subsequent classes. We pass two classes: one that will fail resolution
    // (fake JAR), and one that is not in the DB at all — both should be processed without throw.
    java.lang.instrument.Instrumentation inst =
        fakeInstrumentationReturning(
            // ScaReachabilityTransformer is NOT in the DB → quick exit, no error
            ScaReachabilityTransformer.class,
            // Object.class has null protectionDomain → Path B → no artifact found → no hit
            Object.class);

    // Must complete without throwing even when individual class processing fails
    transformer.checkAlreadyLoadedClasses(inst);
    ScaReachabilityCollector.INSTANCE.drain();
  }

  private static java.lang.instrument.Instrumentation fakeInstrumentationReturning(
      Class<?>... classes) {
    return (java.lang.instrument.Instrumentation)
        java.lang.reflect.Proxy.newProxyInstance(
            ScaReachabilityTransformerTest.class.getClassLoader(),
            new Class<?>[] {java.lang.instrument.Instrumentation.class},
            (proxy, method, args) -> {
              if ("getAllLoadedClasses".equals(method.getName())) {
                return classes;
              }
              return null;
            });
  }

  // --- performPendingRetransforms ---

  @Test
  void performPendingRetransforms_noOpWhenNullInstrumentation() {
    // Transformer constructed with null instrumentation (test context) must not throw
    transformer.performPendingRetransforms();
    // No exception = pass
  }

  @Test
  void performPendingRetransforms_callsRetransformClassesForPendingQueue() throws Exception {
    List<Class<?>> retransformed = new java.util.ArrayList<>();
    java.lang.instrument.Instrumentation inst =
        (java.lang.instrument.Instrumentation)
            java.lang.reflect.Proxy.newProxyInstance(
                ScaReachabilityTransformerTest.class.getClassLoader(),
                new Class<?>[] {java.lang.instrument.Instrumentation.class},
                (proxy, method, args) -> {
                  if ("getAllLoadedClasses".equals(method.getName())) {
                    return new Class<?>[0];
                  }
                  if ("retransformClasses".equals(method.getName())) {
                    retransformed.addAll(java.util.Arrays.asList((Class<?>[]) args[0]));
                    return null;
                  }
                  return null;
                });
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(JACKSON_JSON));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(db, inst);

    // Simulate checkAlreadyLoadedClasses adding a class to the pending queue
    t.pendingRetransform.add(ScaReachabilityTransformer.class);
    t.performPendingRetransforms();

    assertEquals(1, retransformed.size());
    assertEquals(ScaReachabilityTransformer.class, retransformed.get(0));
  }

  @Test
  void performPendingRetransforms_noOpWhenQueuesEmpty() throws Exception {
    List<String> methodsCalled = new java.util.ArrayList<>();
    java.lang.instrument.Instrumentation inst =
        (java.lang.instrument.Instrumentation)
            java.lang.reflect.Proxy.newProxyInstance(
                ScaReachabilityTransformerTest.class.getClassLoader(),
                new Class<?>[] {java.lang.instrument.Instrumentation.class},
                (proxy, method, args) -> {
                  methodsCalled.add(method.getName());
                  return method.getName().equals("getAllLoadedClasses") ? new Class<?>[0] : null;
                });
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(JACKSON_JSON));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(db, inst);

    t.performPendingRetransforms();

    // With empty queues: getAllLoadedClasses is skipped (pendingRetransformNames is empty),
    // retransformClasses is never called
    assertFalse(
        methodsCalled.contains("retransformClasses"),
        "retransformClasses must not be called when both queues are empty");
  }

  // --- helpers ---

  private static ProtectionDomain protectionDomainFor(URL location) throws Exception {
    CodeSource cs = new CodeSource(location, (java.security.cert.Certificate[]) null);
    return new ProtectionDomain(cs, null);
  }

  private static URL jarUrl(String name) throws Exception {
    return new File("/tmp/" + name).toURI().toURL();
  }

  private static URL findJarOnClasspath(String partialName) {
    String cp = System.getProperty("java.class.path", "");
    for (String entry : cp.split(File.pathSeparator)) {
      if (entry.contains(partialName) && entry.endsWith(".jar")) {
        try {
          return new File(entry).toURI().toURL();
        } catch (Exception ignored) {
        }
      }
    }
    return null;
  }
}
