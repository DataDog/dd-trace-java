package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.telemetry.ScaReachabilityCollector;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import datadog.trace.bootstrap.appsec.sca.ScaReachabilityCallback;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for method-level symbol detection ({@code method != null} in sca_cves.json).
 *
 * <p>Strategy: test {@link ScaReachabilityTransformer#injectMethodCallbacks} directly to verify the
 * ASM injection mechanism, decoupled from JAR version resolution. The version resolution path is
 * covered by {@link ScaReachabilityTransformerTest}.
 */
class ScaReachabilityMethodLevelTest {

  /** Target class that the transformer will instrument in tests. */
  public static class TargetClass {
    public String vulnerableMethod() {
      return "executed";
    }

    public String safeMethod() {
      return "safe";
    }
  }

  private ScaCveDatabase db;
  private ScaReachabilityTransformer transformer;

  @BeforeEach
  void setUp() throws Exception {
    ScaReachabilityCollector.INSTANCE.drain();
    // Register the same handler as ScaReachabilitySystem.start() does in production
    ScaReachabilityCallback.register(
        (vulnId, artifact, version, dotClassName, methodName, line) ->
            ScaReachabilityCollector.INSTANCE.addHit(
                new ScaReachabilityHit(vulnId, artifact, version, dotClassName, methodName, line)));
    db = ScaCveDatabase.parse(new StringReader("{\"version\":1,\"entries\":[]}"));
    transformer = new ScaReachabilityTransformer(db, null);
  }

  @AfterEach
  void tearDown() {
    ScaReachabilityCollector.INSTANCE.drain();
    ScaReachabilityCallback.register(null);
  }

  // ---------------------------------------------------------------------------
  // ASM injection: injectMethodCallbacks()
  // ---------------------------------------------------------------------------

  @Test
  void injectMethodCallbacks_returnsModifiedBytecode() throws Exception {
    byte[] original = bytecodeOf(TargetClass.class);
    Map<String, List<ScaReachabilityTransformer.MethodCallbackSpec>> callbacks =
        singleCallback("vulnerableMethod");

    byte[] modified = transformer.injectMethodCallbacks(original, callbacks);

    assertNotNull(modified, "injectMethodCallbacks must return non-null modified bytecode");
  }

  @Test
  void injectMethodCallbacks_callbackFiredOnMethodCall() throws Exception {
    byte[] original = bytecodeOf(TargetClass.class);
    Map<String, List<ScaReachabilityTransformer.MethodCallbackSpec>> callbacks =
        singleCallback("vulnerableMethod");

    byte[] modified = transformer.injectMethodCallbacks(original, callbacks);
    Class<?> cls = loadModified(modified);
    Object instance = cls.getDeclaredConstructor().newInstance();
    cls.getMethod("vulnerableMethod").invoke(instance);

    List<ScaReachabilityHit> hits = ScaReachabilityCollector.INSTANCE.drain();
    assertEquals(1, hits.size());
    ScaReachabilityHit hit = hits.get(0);
    assertEquals("GHSA-method-0001", hit.vulnId());
    assertEquals("com.example:test-lib", hit.artifact());
    assertEquals("1.2.3", hit.version());
    // Callsite semantics: path/symbol/line should be the APPLICATION frame that invoked the
    // vulnerable method. In production (e.g. TargetClass = com.fasterxml.jackson.ObjectMapper),
    // findCallsite() finds the caller and returns it.
    //
    // In this test, TargetClass is in com.datadog.appsec.sca.* which AbstractStackWalker
    // treats as agent code and filters out. findCallsite() returns null and the handler falls
    // back to reporting the vulnerable symbol itself (dotClassName/methodName).
    // This verifies the fallback path works correctly.
    assertFalse(
        hit.className().isEmpty(), "className must be non-empty (fallback: vulnerable class)");
    assertFalse(hit.symbolName().isEmpty(), "symbolName must be non-empty");
    assertTrue(hit.line() >= 0, "line must be non-negative");
  }

  @Test
  void injectMethodCallbacks_noCallbackForSafeMethod() throws Exception {
    byte[] original = bytecodeOf(TargetClass.class);
    Map<String, List<ScaReachabilityTransformer.MethodCallbackSpec>> callbacks =
        singleCallback("vulnerableMethod");

    byte[] modified = transformer.injectMethodCallbacks(original, callbacks);
    Class<?> cls = loadModified(modified);
    Object instance = cls.getDeclaredConstructor().newInstance();
    cls.getMethod("safeMethod").invoke(instance); // call only the safe method

    assertTrue(
        ScaReachabilityCollector.INSTANCE.drain().isEmpty(),
        "No hit expected when only non-instrumented methods are called");
  }

  @Test
  void injectMethodCallbacks_deduplicatesOnMultipleCalls() throws Exception {
    byte[] original = bytecodeOf(TargetClass.class);
    Map<String, List<ScaReachabilityTransformer.MethodCallbackSpec>> callbacks =
        singleCallback("vulnerableMethod");

    byte[] modified = transformer.injectMethodCallbacks(original, callbacks);
    Class<?> cls = loadModified(modified);
    Object instance = cls.getDeclaredConstructor().newInstance();
    Method m = cls.getMethod("vulnerableMethod");

    m.invoke(instance);
    m.invoke(instance);
    m.invoke(instance);

    assertEquals(
        1,
        ScaReachabilityCollector.INSTANCE.drain().size(),
        "Hit must be reported only once regardless of how many times the method is called");
  }

  @Test
  void injectMethodCallbacks_injectsMultipleMethodsIndependently() throws Exception {
    byte[] original = bytecodeOf(TargetClass.class);
    Map<String, List<ScaReachabilityTransformer.MethodCallbackSpec>> callbacks = new HashMap<>();
    callbacks.put(
        "vulnerableMethod",
        Collections.singletonList(
            spec("GHSA-m1", "com.example:lib", "1.0.0", "T", "vulnerableMethod")));
    callbacks.put(
        "safeMethod",
        Collections.singletonList(spec("GHSA-m2", "com.example:lib", "1.0.0", "T", "safeMethod")));

    byte[] modified = transformer.injectMethodCallbacks(original, callbacks);
    Class<?> cls = loadModified(modified);
    Object instance = cls.getDeclaredConstructor().newInstance();

    cls.getMethod("vulnerableMethod").invoke(instance);
    cls.getMethod("safeMethod").invoke(instance);

    List<ScaReachabilityHit> hits = ScaReachabilityCollector.INSTANCE.drain();
    assertEquals(2, hits.size(), "Each instrumented method produces its own hit");
    assertTrue(hits.stream().anyMatch(h -> h.symbolName().equals("vulnerableMethod")));
    assertTrue(hits.stream().anyMatch(h -> h.symbolName().equals("safeMethod")));
  }

  // ---------------------------------------------------------------------------
  // transform(): class-level symbols still report <clinit> via Path A
  // ---------------------------------------------------------------------------

  @Test
  void transformReturnsNullForClassLevelSymbol() throws Exception {
    String json =
        "{\"version\":1,\"entries\":[{"
            + "\"vuln_id\":\"GHSA-cls\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 999.0.0\"],"
            + "\"symbols\":[{\"class\":\""
            + TargetClass.class.getName().replace('.', '/')
            + "\",\"method\":null}]"
            + "}]}";
    ScaCveDatabase classDb = ScaCveDatabase.parse(new StringReader(json));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(classDb, null);

    byte[] result =
        t.transform(
            null,
            TargetClass.class.getName().replace('.', '/'),
            null,
            TargetClass.class.getProtectionDomain(),
            bytecodeOf(TargetClass.class));

    assertNull(result, "transform() must return null (observation only) for class-level symbols");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Map<String, List<ScaReachabilityTransformer.MethodCallbackSpec>> singleCallback(
      String methodName) {
    Map<String, List<ScaReachabilityTransformer.MethodCallbackSpec>> m = new HashMap<>();
    m.put(
        methodName,
        Collections.singletonList(
            spec(
                "GHSA-method-0001",
                "com.example:test-lib",
                "1.2.3",
                TargetClass.class.getName(),
                methodName)));
    return m;
  }

  private static ScaReachabilityTransformer.MethodCallbackSpec spec(
      String vulnId, String artifact, String version, String dotClass, String method) {
    return new ScaReachabilityTransformer.MethodCallbackSpec(
        vulnId, artifact, version, dotClass, method);
  }

  private static byte[] bytecodeOf(Class<?> clazz) throws Exception {
    String path = clazz.getName().replace('.', '/') + ".class";
    try (InputStream is = clazz.getClassLoader().getResourceAsStream(path)) {
      assertNotNull(is, "Cannot load bytecode for " + clazz.getName());
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int n;
      while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
      return buf.toByteArray();
    }
  }

  private static Class<?> loadModified(byte[] bytecode) {
    return new ClassLoader(ScaReachabilityMethodLevelTest.class.getClassLoader()) {
      Class<?> define() {
        return defineClass(null, bytecode, 0, bytecode.length);
      }
    }.define();
  }
}
