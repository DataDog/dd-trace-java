package com.datadog.appsec.sca;

import static com.datadog.appsec.sca.ScaBytecodeTestUtils.bytecodeOf;
import static com.datadog.appsec.sca.ScaBytecodeTestUtils.bytecodeWithoutDebugInfo;
import static com.datadog.appsec.sca.ScaBytecodeTestUtils.loadModified;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry.DependencySnapshot;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import datadog.trace.bootstrap.appsec.sca.ScaReachabilityCallback;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.utility.OpenedClassReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for method-level symbol detection ({@code method != null} in sca_cves.json).
 *
 * <p>Strategy: test {@link ScaMethodCallbackInjector#inject} directly to verify the ASM injection
 * mechanism, decoupled from JAR version resolution. The version resolution path is covered by
 * {@link ScaReachabilityTransformerTest}.
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

  /** Fixture compiled normally, then stripped of line numbers before callback injection. */
  public static class ClassToBeStrippedOfLineNumber {
    // Intentionally non-final so javac emits a field read instead of inlining a constant.
    private static int runtimeFieldValue = 7;

    public static int readField() {
      return runtimeFieldValue;
    }

    public static Object returnArgument(Object value) {
      return value;
    }

    public static String callToString(Object value) {
      return value.toString();
    }
  }

  private ScaCveDatabase db;
  private ScaReachabilityTransformer transformer;

  @BeforeEach
  void setUp() throws Exception {
    ScaReachabilityDependencyRegistry.INSTANCE.resetForTesting();
    // Register the same handler as ScaReachabilitySystem.start() does in production
    ScaReachabilityCallback.register(
        (vulnId, artifact, version, dotClassName, methodName, line) ->
            ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
                artifact, version, vulnId, dotClassName, methodName, line));
    db = ScaCveDatabase.parse(new StringReader("{\"version\":1,\"entries\":[]}"));
    transformer = new ScaReachabilityTransformer(db, null);
  }

  @AfterEach
  void tearDown() {
    ScaReachabilityDependencyRegistry.INSTANCE.resetForTesting();
    ScaReachabilityCallback.register(null);
  }

  // ---------------------------------------------------------------------------
  // ASM injection: ScaMethodCallbackInjector.inject()
  // ---------------------------------------------------------------------------

  @Test
  void inject_returnsModifiedBytecode() throws Exception {
    byte[] original = bytecodeOf(TargetClass.class);
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacks =
        singleCallback("vulnerableMethod");

    byte[] modified = ScaMethodCallbackInjector.inject(original, callbacks);

    assertNotNull(modified, "inject must return non-null modified bytecode");
  }

  @Test
  void inject_callbackFiredOnMethodCall() throws Exception {
    byte[] original = bytecodeOf(TargetClass.class);
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacks =
        singleCallback("vulnerableMethod");

    byte[] modified = ScaMethodCallbackInjector.inject(original, callbacks);
    Class<?> cls = loadModified(modified);
    Object instance = cls.getDeclaredConstructor().newInstance();
    cls.getMethod("vulnerableMethod").invoke(instance);

    List<ScaReachabilityHit> hits = drainHits();
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
  void inject_noCallbackForSafeMethod() throws Exception {
    byte[] original = bytecodeOf(TargetClass.class);
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacks =
        singleCallback("vulnerableMethod");

    byte[] modified = ScaMethodCallbackInjector.inject(original, callbacks);
    Class<?> cls = loadModified(modified);
    Object instance = cls.getDeclaredConstructor().newInstance();
    cls.getMethod("safeMethod").invoke(instance); // call only the safe method

    assertTrue(
        drainHits().isEmpty(), "No hit expected when only non-instrumented methods are called");
  }

  @Test
  void inject_deduplicatesOnMultipleCalls() throws Exception {
    byte[] original = bytecodeOf(TargetClass.class);
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacks =
        singleCallback("vulnerableMethod");

    byte[] modified = ScaMethodCallbackInjector.inject(original, callbacks);
    Class<?> cls = loadModified(modified);
    Object instance = cls.getDeclaredConstructor().newInstance();
    Method m = cls.getMethod("vulnerableMethod");

    m.invoke(instance);
    m.invoke(instance);
    m.invoke(instance);

    assertEquals(
        1,
        drainHits().size(),
        "Hit must be reported only once regardless of how many times the method is called");
  }

  @Test
  void inject_injectsMultipleMethodsIndependently() throws Exception {
    byte[] original = bytecodeOf(TargetClass.class);
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacks = new HashMap<>();
    callbacks.put(
        "vulnerableMethod",
        Collections.singletonList(
            spec("GHSA-m1", "com.example:lib", "1.0.0", "T", "vulnerableMethod")));
    callbacks.put(
        "safeMethod",
        Collections.singletonList(spec("GHSA-m2", "com.example:lib", "1.0.0", "T", "safeMethod")));

    byte[] modified = ScaMethodCallbackInjector.inject(original, callbacks);
    Class<?> cls = loadModified(modified);
    Object instance = cls.getDeclaredConstructor().newInstance();

    cls.getMethod("vulnerableMethod").invoke(instance);
    cls.getMethod("safeMethod").invoke(instance);

    List<ScaReachabilityHit> hits = drainHits();
    assertEquals(2, hits.size(), "Each instrumented method produces its own hit");
    assertTrue(hits.stream().anyMatch(h -> h.symbolName().equals("vulnerableMethod")));
    assertTrue(hits.stream().anyMatch(h -> h.symbolName().equals("safeMethod")));
  }

  @Test
  void inject_withoutLineNumbersInjectsBeforeFirstInstruction() throws Exception {
    byte[] original = bytecodeWithoutDebugInfo(ClassToBeStrippedOfLineNumber.class);
    String className = ClassToBeStrippedOfLineNumber.class.getName();
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacks = new HashMap<>();
    callbacks.put(
        "readField",
        Collections.singletonList(
            spec("GHSA-field", "com.example:lib", "1.0.0", className, "readField")));
    callbacks.put(
        "returnArgument",
        Collections.singletonList(
            spec("GHSA-var", "com.example:lib", "1.0.0", className, "returnArgument")));
    callbacks.put(
        "callToString",
        Collections.singletonList(
            spec("GHSA-method", "com.example:lib", "1.0.0", className, "callToString")));

    Class<?> cls = loadModified(ScaMethodCallbackInjector.inject(original, callbacks));

    assertEquals(7, cls.getMethod("readField").invoke(null));
    assertEquals("value", cls.getMethod("returnArgument", Object.class).invoke(null, "value"));
    assertEquals("value", cls.getMethod("callToString", Object.class).invoke(null, "value"));

    List<ScaReachabilityHit> hits = drainHits();
    assertEquals(3, hits.size());
    assertTrue(hits.stream().allMatch(hit -> hit.line() == 1));
    assertTrue(hits.stream().anyMatch(hit -> hit.symbolName().equals("readField")));
    assertTrue(hits.stream().anyMatch(hit -> hit.symbolName().equals("returnArgument")));
    assertTrue(hits.stream().anyMatch(hit -> hit.symbolName().equals("callToString")));
  }

  @Test
  void inject_sameMethodNameInDifferentClassesProduceIndependentHits() throws Exception {
    // Regression test for dedup key bug: if two classes in the same artifact share a method
    // name (e.g. ClassA.parse and ClassB.parse), both must be reported independently.
    // With the stateful RFC model, one hit per CVE is reported (first occurrence wins).
    // The dedup key in ScaReachabilityCallback uses dotClassName to allow both classes to reach
    // the registry handler, but the registry itself enforces "single occurrence per CVE".
    // This verifies that ClassB's hit does NOT cause a NullPointerException or error — it is
    // simply ignored since ClassA already provided the first callsite for GHSA-shared.

    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacksClassA =
        new HashMap<>();
    callbacksClassA.put(
        "vulnerableMethod",
        Collections.singletonList(
            spec(
                "GHSA-shared",
                "com.example:lib",
                "1.0.0",
                "com.example.ClassA",
                "vulnerableMethod")));

    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacksClassB =
        new HashMap<>();
    callbacksClassB.put(
        "vulnerableMethod",
        Collections.singletonList(
            spec(
                "GHSA-shared",
                "com.example:lib",
                "1.0.0",
                "com.example.ClassB",
                "vulnerableMethod")));

    byte[] original = bytecodeOf(TargetClass.class);
    Class<?> clsA = loadModified(ScaMethodCallbackInjector.inject(original, callbacksClassA));
    Class<?> clsB = loadModified(ScaMethodCallbackInjector.inject(original, callbacksClassB));

    clsA.getMethod("vulnerableMethod").invoke(clsA.getDeclaredConstructor().newInstance());
    clsB.getMethod("vulnerableMethod").invoke(clsB.getDeclaredConstructor().newInstance());

    List<ScaReachabilityHit> hits = drainHits();
    // RFC: "reporting a single occurrence is sufficient" — only the first callsite per CVE is kept.
    assertEquals(1, hits.size(), "Only the first hit per CVE is retained (RFC: single occurrence)");
    assertEquals("GHSA-shared", hits.get(0).vulnId());
  }

  /**
   * Fixture whose methods start with bytecode opcodes that {@code MethodEntryInjector} used to
   * leave unhandled (NEW, INVOKEDYNAMIC, LDC): the injected callback landed after that first
   * instruction instead of at true method entry.
   */
  public static class UnhandledFirstOpcodeMethods {
    public static Object newFirst() {
      return new Object();
    }

    public static Supplier<String> lambdaFirst() {
      return () -> "hi";
    }

    public static String ldcFirst() {
      return "constant".trim();
    }
  }

  @Test
  void inject_withoutLineNumbersInjectsBeforeNewInstruction() throws Exception {
    assertCallbackIsFirstInstruction("newFirst");
  }

  @Test
  void inject_withoutLineNumbersInjectsBeforeInvokeDynamicInstruction() throws Exception {
    assertCallbackIsFirstInstruction("lambdaFirst");
  }

  @Test
  void inject_withoutLineNumbersInjectsBeforeLdcInstruction() throws Exception {
    assertCallbackIsFirstInstruction("ldcFirst");
  }

  /**
   * Regression test for the delayed-injection bug: verifies (via ASM inspection of the injected
   * bytecode, not just observed behavior) that the very first instruction visited in {@code
   * methodName} is the callback's own {@code LDC} of the injected {@code vulnId} — i.e. the
   * callback runs strictly before the method's original first instruction. Before the fix, {@code
   * MethodEntryInjector} did not override {@code visitTypeInsn}/{@code
   * visitInvokeDynamicInsn}/{@code visitLdcInsn}, so for methods starting with {@code NEW}, {@code
   * INVOKEDYNAMIC} or {@code LDC} the original instruction was visited first and the callback was
   * spliced in right after it instead of before it.
   */
  private void assertCallbackIsFirstInstruction(String methodName) throws Exception {
    byte[] original = bytecodeWithoutDebugInfo(UnhandledFirstOpcodeMethods.class);
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacks = new HashMap<>();
    callbacks.put(
        methodName,
        Collections.singletonList(
            spec(
                "GHSA-first-instruction",
                "com.example:lib",
                "1.0.0",
                UnhandledFirstOpcodeMethods.class.getName(),
                methodName)));
    byte[] modified = ScaMethodCallbackInjector.inject(original, callbacks);

    assertEquals(
        "GHSA-first-instruction",
        firstVisitedInstructionOf(modified, methodName),
        "the injected callback's first LDC (vulnId) must be the method's first visited instruction");
  }

  /**
   * Reads {@code methodName} from {@code bytecode} and returns whatever value/marker the very first
   * opcode-visiting callback receives (the LDC constant for {@code visitLdcInsn}, or the opcode
   * name for other instruction kinds).
   */
  private static Object firstVisitedInstructionOf(byte[] bytecode, String methodName) {
    Object[] first = {null};
    new ClassReader(bytecode)
        .accept(
            new ClassVisitor(OpenedClassReader.ASM_API) {
              @Override
              public MethodVisitor visitMethod(
                  int access, String name, String descriptor, String signature, String[] exc) {
                if (!name.equals(methodName)) {
                  return null;
                }
                return new MethodVisitor(OpenedClassReader.ASM_API) {
                  @Override
                  public void visitLdcInsn(Object value) {
                    if (first[0] == null) {
                      first[0] = value;
                    }
                  }

                  @Override
                  public void visitTypeInsn(int opcode, String type) {
                    if (first[0] == null) {
                      first[0] = "TYPE_INSN:" + opcode;
                    }
                  }

                  @Override
                  public void visitInvokeDynamicInsn(String n, String d, Handle h, Object... args) {
                    if (first[0] == null) {
                      first[0] = "INVOKEDYNAMIC";
                    }
                  }
                };
              }
            },
            0);
    assertNotNull(first[0], "no matching instruction found in " + methodName);
    return first[0];
  }

  /**
   * Fixture exercising the instruction kinds {@link ScaMethodCallbackInjector.MethodEntryInjector}
   * must forward to the delegate unchanged: BIPUSH ({@code visitIntInsn}), a conditional jump
   * ({@code visitJumpInsn}), a loop increment ({@code visitIincInsn}), a dense switch ({@code
   * visitTableSwitchInsn}), a sparse switch ({@code visitLookupSwitchInsn}) and a 2D array
   * allocation ({@code visitMultiANewArrayInsn}).
   */
  public static class OpcodeCoverageMethods {
    public static int intInsn() {
      return 100;
    }

    public static int jumpInsn(boolean flag) {
      if (flag) {
        return 1;
      }
      return 2;
    }

    public static int iincInsn() {
      int i = 0;
      i++;
      return i;
    }

    public static int tableSwitchInsn(int x) {
      switch (x) {
        case 0:
          return 1;
        case 1:
          return 2;
        case 2:
          return 3;
        default:
          return -1;
      }
    }

    public static int lookupSwitchInsn(int x) {
      switch (x) {
        case 0:
          return 1;
        case 100:
          return 2;
        case 10000:
          return 3;
        default:
          return -1;
      }
    }

    public static int[][] multiANewArrayInsn() {
      return new int[2][3];
    }
  }

  @Test
  void inject_preservesBehaviorForAllInstructionKinds() throws Exception {
    // Coverage for MethodEntryInjector's visitIntInsn/visitJumpInsn/visitIincInsn/
    // visitTableSwitchInsn/visitLookupSwitchInsn/visitMultiANewArrayInsn overrides: each must
    // forward to the delegate unchanged so the original method body still executes correctly
    // once the entry callback is spliced in.
    byte[] original = bytecodeOf(OpcodeCoverageMethods.class);
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacks = new HashMap<>();
    for (String methodName :
        new String[] {
          "intInsn",
          "jumpInsn",
          "iincInsn",
          "tableSwitchInsn",
          "lookupSwitchInsn",
          "multiANewArrayInsn"
        }) {
      callbacks.put(
          methodName,
          Collections.singletonList(
              spec("GHSA-" + methodName, "com.example:lib", "1.0.0", "T", methodName)));
    }

    Class<?> cls = loadModified(ScaMethodCallbackInjector.inject(original, callbacks));

    assertEquals(100, cls.getMethod("intInsn").invoke(null));
    assertEquals(1, cls.getMethod("jumpInsn", boolean.class).invoke(null, true));
    assertEquals(2, cls.getMethod("jumpInsn", boolean.class).invoke(null, false));
    assertEquals(1, cls.getMethod("iincInsn").invoke(null));
    assertEquals(2, cls.getMethod("tableSwitchInsn", int.class).invoke(null, 1));
    assertEquals(2, cls.getMethod("lookupSwitchInsn", int.class).invoke(null, 100));
    assertEquals(2, ((int[][]) cls.getMethod("multiANewArrayInsn").invoke(null)).length);

    assertEquals(6, drainHits().size(), "each instrumented method fires its own callback");
  }

  // ---------------------------------------------------------------------------
  // transform(): two-phase design — first load enqueues, retransform injects
  // ---------------------------------------------------------------------------

  @Test
  void transform_firstLoad_schedulesRetransformAndReturnsNull() throws Exception {
    String json =
        "{\"version\":1,\"entries\":[{"
            + "\"vuln_id\":\"GHSA-cls\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 999.0.0\"],"
            + "\"symbols\":[{\"class\":\""
            + TargetClass.class.getName().replace('.', '/')
            + "\",\"method\":\"vulnerableMethod\"}]"
            + "}]}";
    ScaCveDatabase classDb = ScaCveDatabase.parse(new StringReader(json));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(classDb, null);

    byte[] result =
        t.transform(
            null,
            TargetClass.class.getName().replace('.', '/'),
            null, // classBeingRedefined == null → first load path
            TargetClass.class.getProtectionDomain(),
            bytecodeOf(TargetClass.class));

    assertNull(result, "First load must return null (JAR I/O deferred to periodic task)");
    assertFalse(
        t.pendingRetransformNames.isEmpty(),
        "First load must add the class name to pendingRetransformNames for the next heartbeat");
  }

  @Test
  void transform_retransform_processesInlineAndDoesNotReSchedule() throws Exception {
    // On retransform (classBeingRedefined != null), transform() calls processClass() inline.
    // Version resolution fails in the unit-test context (no real JAR for com.example:lib), so
    // processClass() re-queues in pendingRetransformNames for a retry. This exercises a single
    // unresolved attempt (attempt 1 of ScaReachabilityTransformer.MAX_UNRESOLVED_RETRIES = 5), so
    // re-queueing here is the expected, unchanged behaviour: the cap only stops the retries once it
    // is exhausted, which transform_retransform_stopsReQueueingAfterMaxUnresolvedRetries covers.
    // The invariant asserted here is that the retransform path reaches processClass() rather than
    // the first-load fast-path.
    String json =
        "{\"version\":1,\"entries\":[{"
            + "\"vuln_id\":\"GHSA-mth\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 999.0.0\"],"
            + "\"symbols\":[{\"class\":\""
            + TargetClass.class.getName().replace('.', '/')
            + "\",\"method\":\"vulnerableMethod\"}]"
            + "}]}";
    ScaCveDatabase methodDb = ScaCveDatabase.parse(new StringReader(json));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(methodDb, null);

    // Start clean: no pending retransforms
    assertTrue(t.pendingRetransformNames.isEmpty());

    t.transform(
        null,
        TargetClass.class.getName().replace('.', '/'),
        TargetClass.class, // classBeingRedefined != null → retransform path
        TargetClass.class.getProtectionDomain(),
        bytecodeOf(TargetClass.class));

    // Version resolution failed (no pom.properties for com.example:lib in test classpath), so
    // processClass() re-queued the class for a retry on the next heartbeat -- this is only attempt
    // 1, well below the cap. This confirms the retransform path reached processClass() rather than
    // the first-load path.
    assertFalse(
        t.pendingRetransformNames.isEmpty(),
        "processClass() must re-queue on version resolution failure for heartbeat retry");
  }

  @Test
  void transform_retransform_stopsReQueueingAfterMaxUnresolvedRetries() throws Exception {
    // APPSEC-69734: an artifact that can never resolve as a dependency (com.example:lib has no
    // pom.properties anywhere in the test classpath, just like an embedded-Tomcat app never
    // resolving "tomcat"/"tomcat-coyote") must not re-queue itself into pendingRetransformNames
    // forever — every heartbeat would otherwise cost a stop-the-world retransformClasses() call.
    String json =
        "{\"version\":1,\"entries\":[{"
            + "\"vuln_id\":\"GHSA-cap\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 999.0.0\"],"
            + "\"symbols\":[{\"class\":\""
            + TargetClass.class.getName().replace('.', '/')
            + "\",\"method\":\"vulnerableMethod\"}]"
            + "}]}";
    ScaCveDatabase methodDb = ScaCveDatabase.parse(new StringReader(json));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(methodDb, null);

    int maxRetries = ScaReachabilityTransformer.MAX_UNRESOLVED_RETRIES;

    // Heartbeats 1..maxRetries-1 stay within the cap and keep re-queuing. The Nth heartbeat is the
    // one where the attempt count reaches maxRetries and processClass() gives up instead.
    for (int attempt = 1; attempt < maxRetries; attempt++) {
      // Each iteration stands for one telemetry heartbeat. Clearing pendingRetransformNames first
      // makes each iteration's assertion meaningful on its own — otherwise a leftover entry from an
      // earlier attempt would keep the set non-empty even if this attempt's re-queue were broken.
      t.pendingRetransformNames.clear();
      simulateHeartbeat(t);
      assertFalse(
          t.pendingRetransformNames.isEmpty(),
          "attempt " + attempt + " is within the cap, so the class must still be queued for retry");
    }

    // The cap is reached on this heartbeat (attempt count == maxRetries): from now on
    // processClass() must not re-add the class. Clearing the set makes that unambiguous — anything
    // present afterwards can only have been re-added by the call below.
    t.pendingRetransformNames.clear();
    simulateHeartbeat(t);

    assertTrue(
        t.pendingRetransformNames.isEmpty(),
        "after MAX_UNRESOLVED_RETRIES the class must be given up on and never re-queued again");
  }

  @Test
  void transform_retransform_resolvingBeforeTheCapStillInjectsTheCallback() throws Exception {
    // APPSEC-69734: the retry cap must only ever kick in for artifacts that never resolve. An
    // artifact that stays unresolved for a few heartbeats and then finally resolves (e.g. its JAR
    // is only scanned successfully on a later pass) must behave exactly as before the cap existed:
    // the method-level callback is injected on that attempt, and nothing is given up on early.
    String json =
        "{\"version\":1,\"entries\":[{"
            + "\"vuln_id\":\"GHSA-late\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 999.0.0\"],"
            + "\"symbols\":[{\"class\":\""
            + TargetClass.class.getName().replace('.', '/')
            + "\",\"method\":\"vulnerableMethod\"}]"
            + "}]}";
    ScaCveDatabase methodDb = ScaCveDatabase.parse(new StringReader(json));
    ScaReachabilityTransformer t = new ScaReachabilityTransformer(methodDb, null);

    // Attempts 1..3: com.example:lib has no pom.properties in the test classpath, so resolution
    // fails and the class is re-queued (3 < MAX_UNRESOLVED_RETRIES).
    for (int attempt = 1; attempt <= 3; attempt++) {
      t.pendingRetransformNames.clear();
      assertNull(
          simulateHeartbeat(t),
          "attempt " + attempt + " cannot inject anything while the artifact is unresolved");
      assertTrue(
          t.pendingRetransformNames.contains(TargetClass.class.getName().replace('.', '/')),
          "attempt " + attempt + " is well within the cap, so the class must be re-queued");
    }

    // Attempt 4 (still before the 5th and final allowed attempt): make resolution succeed by
    // seeding the classpath-scan cache that resolveArtifactDep() consults before scanning, which
    // is exactly what a successful findArtifactInClasspath() would have populated.
    t.classpathArtifactCache.put(
        "com.example:lib", new Dependency("com.example:lib", "1.0.0", "lib-1.0.0.jar", null));
    t.pendingRetransformNames.clear();

    byte[] modified = simulateHeartbeat(t);

    assertNotNull(modified, "once the artifact resolves, the callback bytecode must be returned");
    assertTrue(
        t.pendingRetransformNames.isEmpty(),
        "nothing is left unresolved, so there is nothing to re-queue");

    Class<?> cls = loadModified(modified);
    cls.getMethod("vulnerableMethod").invoke(cls.getDeclaredConstructor().newInstance());

    List<ScaReachabilityHit> hits = drainHits();
    assertEquals(1, hits.size(), "the injected callback must fire like it did before the cap");
    assertEquals("GHSA-late", hits.get(0).vulnId());
    assertEquals("com.example:lib", hits.get(0).artifact());
    assertEquals("1.0.0", hits.get(0).version());
  }

  /**
   * Simulates one telemetry heartbeat: clears the per-heartbeat dedup marker (only {@link
   * ScaReachabilityTransformer#performPendingRetransforms()} does this in production; done here by
   * hand because these tests drive {@code transform()} directly), then fires the retransform path
   * ({@code classBeingRedefined != null}) for {@link TargetClass}, returning whatever bytecode
   * {@code processClass()} produced ({@code null} while the watched artifact stays unresolved).
   */
  private static byte[] simulateHeartbeat(ScaReachabilityTransformer t) throws Exception {
    t.countedThisHeartbeat.clear();
    return t.transform(
        null,
        TargetClass.class.getName().replace('.', '/'),
        TargetClass.class,
        TargetClass.class.getProtectionDomain(),
        bytecodeOf(TargetClass.class));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Extracts ScaReachabilityHit objects from pending dependencies in the registry. Only returns
   * CVEs that have an actual hit (callsite recorded), not empty-reached CVEs.
   */
  private static List<ScaReachabilityHit> drainHits() {
    List<ScaReachabilityHit> result = new ArrayList<>();
    for (DependencySnapshot dep :
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies()) {
      for (ScaReachabilityDependencyRegistry.CveSnapshot cve : dep.cves) {
        if (cve.hit != null) {
          result.add(cve.hit);
        }
      }
    }
    return result;
  }

  private static Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> singleCallback(
      String methodName) {
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> m = new HashMap<>();
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

  private static ScaMethodCallbackInjector.MethodCallbackSpec spec(
      String vulnId, String artifact, String version, String dotClass, String method) {
    return new ScaMethodCallbackInjector.MethodCallbackSpec(
        vulnId, artifact, version, dotClass, method);
  }
}
