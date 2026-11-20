package com.datadog.debugger.agent;

import static com.datadog.debugger.el.expressions.BooleanExpression.FALSE;
import static com.datadog.debugger.el.expressions.BooleanExpression.TRUE;
import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static utils.InstrumentationTestHelper.compileAndLoadClass;
import static utils.InstrumentationTestHelper.getLineForLineProbe;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.expressions.BooleanExpression;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.util.TestSnapshotListener;
import datadog.context.Context;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.sampling.Sampler;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.core.CoreTracer;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.stream.Stream;
import org.joor.Reflect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class CoordinatedSamplingTest extends CapturingTestBase {
  private static final ProbeId PROBE_ID1 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f6", 0);
  private static final ProbeId PROBE_ID2 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f7", 0);
  private static final ProbeId PROBE_ID3 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final ProbeId LINE_PROBE_ID1 =
      new ProbeId("beae1817-f3b0-4ea8-a74f-000000000001", 0);
  private static final ProbeId LINE_PROBE_ID2 =
      new ProbeId("beae1817-f3b0-4ea8-a74f-000000000002", 0);
  private static final ProbeId LINE_PROBE_ID3 =
      new ProbeId("beae1817-f3b0-4ea8-a74f-000000000003", 0);
  private static final ProbeId PROBE_ID_A = new ProbeId("beae1827-f3b0-4ea8-a74f-00000000000a", 0);
  private static final ProbeId PROBE_ID_B = new ProbeId("beae1827-f3b0-4ea8-a74f-00000000000b", 0);
  private static final ProbeId PROBE_ID_C = new ProbeId("beae1827-f3b0-4ea8-a74f-00000000000c", 0);
  private static final ProbeId PROBE_ID_D = new ProbeId("beae1827-f3b0-4ea8-a74f-00000000000d", 0);
  private static final ProbeId PROBE_ID_E = new ProbeId("beae1827-f3b0-4ea8-a74f-00000000000e", 0);
  private static final String BUDGET_CLASS_NAME = "com.datadog.debugger.CapturedSnapshot34";

  interface TestListenerMethod {
    TestSnapshotListener run() throws IOException, URISyntaxException;
  }

  @Test
  public void coordinatedSamplingFirstEmit() throws IOException, URISyntaxException {
    TestSnapshotListener listener = doCoordinatedSamplingTest(this::coordinatedSampling, 1);
    assertSnapshots(listener, 3, PROBE_ID3, PROBE_ID2, PROBE_ID1);
    assertSame(Context.root(), Context.current());
  }

  @Test
  public void coordinatedSamplingFirstDrop() throws IOException, URISyntaxException {
    TestSnapshotListener listener = doCoordinatedSamplingTest(this::coordinatedSampling, 0);
    assertSnapshots(listener, 0);
  }

  @ParameterizedTest
  @MethodSource("coordinatedSamplingConditionSource")
  public void coordinatedSamplingCondition(
      BooleanExpression cond1,
      BooleanExpression cond2,
      BooleanExpression cond3,
      int numSamples,
      int expectedSnapshots,
      ProbeId... probeIds)
      throws IOException, URISyntaxException {
    TestSnapshotListener listener =
        doCoordinatedSamplingTest(
            () -> coordinatedSamplingWithCondition(cond1, cond2, cond3), numSamples);
    assertSnapshots(listener, expectedSnapshots, probeIds);
  }

  private static Stream<Arguments> coordinatedSamplingConditionSource() {
    return Stream.of(
        arguments(FALSE, FALSE, FALSE, 1, 0, new ProbeId[] {}),
        arguments(TRUE, FALSE, FALSE, 1, 1, new ProbeId[] {PROBE_ID1}),
        arguments(TRUE, TRUE, FALSE, 1, 2, new ProbeId[] {PROBE_ID2, PROBE_ID1}),
        arguments(TRUE, TRUE, TRUE, 1, 3, new ProbeId[] {PROBE_ID3, PROBE_ID2, PROBE_ID1}),
        arguments(FALSE, FALSE, TRUE, 1, 1, new ProbeId[] {PROBE_ID3}),
        arguments(FALSE, TRUE, TRUE, 1, 2, new ProbeId[] {PROBE_ID3, PROBE_ID2}),
        arguments(FALSE, TRUE, FALSE, 1, 1, new ProbeId[] {PROBE_ID2}),
        arguments(TRUE, TRUE, TRUE, 0, 0, new ProbeId[] {}));
  }

  @Test
  public void coordinatedSamplingLoopFirstEmit() throws IOException, URISyntaxException {
    TestSnapshotListener listener = doCoordinatedSamplingTest(this::coordinatedSamplingLoop, 10);
    assertSnapshots(listener, 2, PROBE_ID3, PROBE_ID1);
  }

  @Test
  public void coordinatedSamplingSiblingFirstEmit() throws IOException, URISyntaxException {
    TestSnapshotListener listener = doCoordinatedSamplingTest(this::coordinatedSamplingSibling, 10);
    assertSnapshots(listener, 3, PROBE_ID1, PROBE_ID2, PROBE_ID3);
  }

  @Test
  public void coordinatedSamplingLineFirstEmit() throws IOException, URISyntaxException {
    TestSnapshotListener listener = doCoordinatedSamplingTest(this::lineCoordinatedSampling, 10);
    assertSnapshots(listener, 3, LINE_PROBE_ID1, LINE_PROBE_ID2, LINE_PROBE_ID3);
  }

  @Test
  public void coordinatedSamplingLineFirstDrop() throws IOException, URISyntaxException {
    TestSnapshotListener listener = doCoordinatedSamplingTest(this::lineCoordinatedSampling, 0);
    assertSnapshots(listener, 0);
  }

  @ParameterizedTest
  @MethodSource("coordinatedSamplingLineConditionSource")
  public void coordinatedSamplingLineCondition(
      BooleanExpression cond1,
      BooleanExpression cond2,
      BooleanExpression cond3,
      int numSamples,
      int expectedSnapshots,
      ProbeId... probeIds)
      throws IOException, URISyntaxException {
    TestSnapshotListener listener =
        doCoordinatedSamplingTest(
            () -> coordinatedSamplingLineWithCondition(cond1, cond2, cond3), numSamples);
    assertSnapshots(listener, expectedSnapshots, probeIds);
  }

  private static Stream<Arguments> coordinatedSamplingLineConditionSource() {
    return Stream.of(
        arguments(FALSE, FALSE, FALSE, 1, 0, new ProbeId[] {}),
        arguments(TRUE, FALSE, FALSE, 1, 1, new ProbeId[] {LINE_PROBE_ID1}),
        arguments(TRUE, TRUE, FALSE, 1, 2, new ProbeId[] {LINE_PROBE_ID1, LINE_PROBE_ID2}),
        arguments(
            TRUE, TRUE, TRUE, 1, 3, new ProbeId[] {LINE_PROBE_ID1, LINE_PROBE_ID2, LINE_PROBE_ID3}),
        arguments(FALSE, FALSE, TRUE, 1, 1, new ProbeId[] {LINE_PROBE_ID3}),
        arguments(FALSE, TRUE, TRUE, 1, 2, new ProbeId[] {LINE_PROBE_ID2, LINE_PROBE_ID3}),
        arguments(FALSE, TRUE, FALSE, 1, 1, new ProbeId[] {LINE_PROBE_ID2}),
        arguments(TRUE, TRUE, TRUE, 0, 0, new ProbeId[] {}));
  }

  @Test
  public void noCoordinatedSamplingLogTemplate() throws IOException, URISyntaxException {
    TestSnapshotListener listener =
        doCoordinatedSamplingTest(this::coordinatedSamplingLogTemplate, 1);
    // only one snapshot, the other probes do not benefit from coordinated sampling
    assertSnapshots(listener, 1);
  }

  @Test
  public void coordinatedSamplingIsScopedToLocalRootSpan() throws IOException, URISyntaxException {
    MockSampler probeSampler = new MockSampler(1);
    ProbeRateLimiter.setSamplerSupplier(rate -> probeSampler);
    try {
      tracer = CoreTracer.builder().build();
      TracerInstaller.forceInstallGlobalTracer(tracer);
      final String className = "com.datadog.debugger.CapturedSnapshot21";
      LogProbe probe1 = createMethodProbeAtExit(PROBE_ID1, className, "process1", null);
      LogProbe probe2 = createMethodProbeAtExit(PROBE_ID2, className, "process2", null);
      LogProbe probe3 = createMethodProbeAtExit(PROBE_ID3, className, "process3", null);
      TestSnapshotListener listener = installProbes(probe1, probe2, probe3);
      Class<?> testClass = compileAndLoadClass(className);

      Reflect.onClass(testClass).call("main", "1").get();
      Reflect.onClass(testClass).call("main", "1").get();

      assertSnapshots(listener, 3, PROBE_ID3, PROBE_ID2, PROBE_ID1);
      assertEquals(2, probeSampler.getCallCount());
      assertSame(Context.root(), Context.current());
    } finally {
      ProbeRateLimiter.setSamplerSupplier(null);
    }
  }

  @Test
  public void logProbesIgnoreCoordinatedSampling() throws IOException, URISyntaxException {
    MockSampler probeSampler = new MockSampler(2);
    ProbeRateLimiter.setSamplerSupplier(rate -> probeSampler);
    try {
      tracer = CoreTracer.builder().build();
      TracerInstaller.forceInstallGlobalTracer(tracer);
      final String className = "com.datadog.debugger.CapturedSnapshot21";
      LogProbe logProbe2 =
          createProbeBuilder(PROBE_ID2)
              .where(className, "process2")
              .template("arg={arg}", parseTemplate("arg={arg}"))
              .captureSnapshot(false)
              .build();
      LogProbe logProbe3 =
          createProbeBuilder(PROBE_ID3)
              .where(className, "process3")
              .template("arg={arg}", parseTemplate("arg={arg}"))
              .captureSnapshot(false)
              .build();
      LogProbe snapshotProbe = createMethodProbeAtExit(PROBE_ID1, className, "process1", null);
      TestSnapshotListener listener = installProbes(snapshotProbe, logProbe2, logProbe3);
      Class<?> testClass = compileAndLoadClass(className);

      Reflect.onClass(testClass).call("main", "1").get();

      assertEquals(2, listener.snapshots.size());
      assertTrue(
          listener.snapshots.stream()
              .anyMatch(snapshot -> snapshot.getProbe().getId().equals(PROBE_ID1.getId())));
      assertTrue(
          listener.snapshots.stream()
              .anyMatch(snapshot -> !snapshot.getProbe().getId().equals(PROBE_ID1.getId())));
      assertEquals(3, probeSampler.getCallCount());
    } finally {
      ProbeRateLimiter.setSamplerSupplier(null);
    }
  }

  /**
   * Entry points A, C and D all call B. Each parent is limited to one snapshot per window, while B
   * follows each parent decision: B captures once per sampled request, so 3 times, not once.
   */
  @Test
  public void parentProbesLimitedAndChildCapturedForEachSampledParent()
      throws IOException, URISyntaxException {
    MockSampler globalSampler = installGlobalSampler(Integer.MAX_VALUE);
    MockSampler samplerA = new MockSampler(1);
    MockSampler samplerB = new MockSampler(1);
    MockSampler samplerC = new MockSampler(1);
    MockSampler samplerD = new MockSampler(1);
    TestSnapshotListener listener =
        installBudgetProbes(
            probe(PROBE_ID_A, "entryA", samplerA),
            probe(PROBE_ID_B, "processB", samplerB),
            probe(PROBE_ID_C, "entryC", samplerC),
            probe(PROBE_ID_D, "entryD", samplerD));
    Class<?> testClass = compileAndLoadClass(BUDGET_CLASS_NAME);

    int requestsPerEntryPoint = 500;
    for (int i = 0; i < requestsPerEntryPoint; i++) {
      Reflect.onClass(testClass).call("main", "A").get();
      Reflect.onClass(testClass).call("main", "C").get();
      Reflect.onClass(testClass).call("main", "D").get();
    }

    assertEquals(6, listener.snapshots.size());
    assertEquals(1, countSnapshots(listener, PROBE_ID_A));
    assertEquals(1, countSnapshots(listener, PROBE_ID_C));
    assertEquals(1, countSnapshots(listener, PROBE_ID_D));
    assertEquals(3, countSnapshots(listener, PROBE_ID_B));
    // each parent makes the decision for every request it starts
    assertEquals(requestsPerEntryPoint, samplerA.getCallCount());
    assertEquals(requestsPerEntryPoint, samplerC.getCallCount());
    assertEquals(requestsPerEntryPoint, samplerD.getCallCount());
    // B never makes its own decision: it only follows its parent
    assertEquals(0, samplerB.getCallCount());
    assertEquals(3 * requestsPerEntryPoint, globalSampler.getCallCount());
  }

  /** When the parent is rate limited for a request, the child skips that request too. */
  @Test
  public void parentRateLimitedSkipsChildForSameRequest() throws IOException, URISyntaxException {
    installGlobalSampler(Integer.MAX_VALUE);
    MockSampler samplerA = new MockSampler(1);
    MockSampler samplerB = new MockSampler();
    TestSnapshotListener listener =
        installBudgetProbes(
            probe(PROBE_ID_A, "entryA", samplerA), probe(PROBE_ID_B, "processB", samplerB));
    Class<?> testClass = compileAndLoadClass(BUDGET_CLASS_NAME);

    int requests = 10;
    for (int i = 0; i < requests; i++) {
      Reflect.onClass(testClass).call("main", "A").get();
    }

    assertSnapshots(listener, 2, PROBE_ID_B, PROBE_ID_A);
    assertEquals(requests, samplerA.getCallCount());
    // B does not make a new decision for the requests skipped by A
    assertEquals(0, samplerB.getCallCount());
  }

  /**
   * A probe hit many times in a loop captures only once per request, and does not prevent another
   * probe of the same request from capturing.
   */
  @Test
  public void loopProbeCapturedOncePerRequest() throws IOException, URISyntaxException {
    installGlobalSampler(Integer.MAX_VALUE);
    MockSampler samplerLoop = new MockSampler();
    MockSampler samplerB = new MockSampler();
    MockSampler samplerE = new MockSampler();
    TestSnapshotListener listener =
        installBudgetProbes(
            probe(PROBE_ID_A, "entryLoop", samplerLoop),
            probe(PROBE_ID_B, "processB", samplerB),
            probe(PROBE_ID_E, "processE", samplerE));
    Class<?> testClass = compileAndLoadClass(BUDGET_CLASS_NAME);

    int requests = 3;
    for (int i = 0; i < requests; i++) {
      Reflect.onClass(testClass).call("main", "loop").get();
    }

    assertEquals(3 * requests, listener.snapshots.size());
    assertEquals(requests, countSnapshots(listener, PROBE_ID_A));
    assertEquals(requests, countSnapshots(listener, PROBE_ID_B));
    assertEquals(requests, countSnapshots(listener, PROBE_ID_E));
    assertEquals(requests, samplerLoop.getCallCount());
    assertEquals(0, samplerB.getCallCount());
    assertEquals(0, samplerE.getCallCount());
  }

  /**
   * The global limit applies to parent decisions: once it is reached, parents stop capturing new
   * requests, and so do their children.
   */
  @Test
  public void globalLimitAppliesToParentDecisions() throws IOException, URISyntaxException {
    int globalLimit = 2;
    MockSampler globalSampler = installGlobalSampler(globalLimit);
    MockSampler samplerB = new MockSampler();
    TestSnapshotListener listener =
        installBudgetProbes(
            probe(PROBE_ID_A, "entryA", new MockSampler()),
            probe(PROBE_ID_B, "processB", samplerB),
            probe(PROBE_ID_C, "entryC", new MockSampler()),
            probe(PROBE_ID_D, "entryD", new MockSampler()));
    Class<?> testClass = compileAndLoadClass(BUDGET_CLASS_NAME);

    int requestsPerEntryPoint = 10;
    for (int i = 0; i < requestsPerEntryPoint; i++) {
      Reflect.onClass(testClass).call("main", "A").get();
      Reflect.onClass(testClass).call("main", "C").get();
      Reflect.onClass(testClass).call("main", "D").get();
    }

    // only the first 2 requests (A then C) are sampled, each producing a parent and a B snapshot
    assertEquals(2 * globalLimit, listener.snapshots.size());
    assertEquals(1, countSnapshots(listener, PROBE_ID_A));
    assertEquals(1, countSnapshots(listener, PROBE_ID_C));
    assertEquals(0, countSnapshots(listener, PROBE_ID_D));
    assertEquals(globalLimit, countSnapshots(listener, PROBE_ID_B));
    // one global check per parent decision
    assertEquals(3 * requestsPerEntryPoint, globalSampler.getCallCount());
    assertEquals(0, samplerB.getCallCount());
  }

  private static MockSampler installGlobalSampler(int numSamples) {
    MockSampler globalSampler = new MockSampler(numSamples);
    ProbeRateLimiter.setSamplerSupplier(rate -> globalSampler);
    try {
      // rate value is ignored: the supplier always returns the mock
      ProbeRateLimiter.setGlobalSnapshotRate(1);
    } finally {
      ProbeRateLimiter.setSamplerSupplier(null);
    }
    return globalSampler;
  }

  /** A method probe on the budget test class paired with the sampler it must use. */
  private static final class ProbeWithSampler {
    final LogProbe probe;
    final Sampler sampler;

    ProbeWithSampler(LogProbe probe, Sampler sampler) {
      this.probe = probe;
      this.sampler = sampler;
    }
  }

  private static ProbeWithSampler probe(ProbeId id, String methodName, Sampler sampler) {
    return new ProbeWithSampler(
        createMethodProbeAtExit(id, BUDGET_CLASS_NAME, methodName, null), sampler);
  }

  private TestSnapshotListener installBudgetProbes(ProbeWithSampler... probes) {
    CoreTracer tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    TestSnapshotListener listener =
        installProbes(Arrays.stream(probes).map(p -> p.probe).toArray(LogProbe[]::new));
    // installProbes re-initializes samplers, so give each probe its own sampler afterward
    for (ProbeWithSampler p : probes) {
      Reflect.on(p.probe).set("sampler", p.sampler);
    }
    return listener;
  }

  private static long countSnapshots(TestSnapshotListener listener, ProbeId probeId) {
    return listener.snapshots.stream()
        .filter(snapshot -> snapshot.getProbe().getId().equals(probeId.getId()))
        .count();
  }

  private TestSnapshotListener coordinatedSampling() throws IOException, URISyntaxException {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot21";
    LogProbe probe1 = createMethodProbeAtExit(PROBE_ID1, CLASS_NAME, "process1", null);
    LogProbe probe2 = createMethodProbeAtExit(PROBE_ID2, CLASS_NAME, "process2", null);
    LogProbe probe3 = createMethodProbeAtExit(PROBE_ID3, CLASS_NAME, "process3", null);
    TestSnapshotListener listener = installProbes(probe1, probe2, probe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.onClass(testClass).call("main", "1").get();
    return listener;
  }

  private TestSnapshotListener lineCoordinatedSampling() throws IOException, URISyntaxException {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot21";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    LogProbe probe1 = createLineProbe(LINE_PROBE_ID1, CLASS_NAME, line);
    line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID2);
    LogProbe probe2 = createLineProbe(LINE_PROBE_ID2, CLASS_NAME, line);
    line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID3);
    LogProbe probe3 = createLineProbe(LINE_PROBE_ID3, CLASS_NAME, line);
    TestSnapshotListener listener = installProbes(probe1, probe2, probe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.onClass(testClass).call("main", "1").get();
    return listener;
  }

  private TestSnapshotListener coordinatedSamplingWithCondition(
      BooleanExpression cond1, BooleanExpression cond2, BooleanExpression cond3)
      throws IOException, URISyntaxException {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot21";
    LogProbe probe1 =
        createProbeBuilder(PROBE_ID1, CLASS_NAME, "process1", null)
            .when(new ProbeCondition(DSL.when(cond1), ""))
            .build();
    LogProbe probe2 =
        createProbeBuilder(PROBE_ID2, CLASS_NAME, "process2", null)
            .when(new ProbeCondition(DSL.when(cond2), ""))
            .build();
    LogProbe probe3 =
        createProbeBuilder(PROBE_ID3, CLASS_NAME, "process3", null)
            .when(new ProbeCondition(DSL.when(cond3), ""))
            .build();
    TestSnapshotListener listener = installProbes(probe1, probe2, probe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.onClass(testClass).call("main", "1").get();
    return listener;
  }

  private TestSnapshotListener coordinatedSamplingLineWithCondition(
      BooleanExpression cond1, BooleanExpression cond2, BooleanExpression cond3)
      throws IOException, URISyntaxException {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot21";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    LogProbe probe1 =
        createProbeBuilder(LINE_PROBE_ID1, CLASS_NAME, line)
            .when(new ProbeCondition(DSL.when(cond1), ""))
            .build();
    line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID2);
    LogProbe probe2 =
        createProbeBuilder(LINE_PROBE_ID2, CLASS_NAME, line)
            .when(new ProbeCondition(DSL.when(cond2), ""))
            .build();
    line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID3);
    LogProbe probe3 =
        createProbeBuilder(LINE_PROBE_ID3, CLASS_NAME, line)
            .when(new ProbeCondition(DSL.when(cond3), ""))
            .build();
    TestSnapshotListener listener = installProbes(probe1, probe2, probe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.onClass(testClass).call("main", "1").get();
    return listener;
  }

  private TestSnapshotListener coordinatedSamplingLoop() throws IOException, URISyntaxException {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot21";
    LogProbe probeRoot = createMethodProbeAtExit(PROBE_ID1, CLASS_NAME, "rootLoopProcess", null);
    LogProbe probe3 = createMethodProbeAtExit(PROBE_ID3, CLASS_NAME, "process3", null);
    TestSnapshotListener listener = installProbes(probeRoot, probe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.onClass(testClass).call("main", "loop").get();
    return listener;
  }

  private TestSnapshotListener coordinatedSamplingSibling() throws IOException, URISyntaxException {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot21";
    LogProbe probe1 = createMethodProbeAtExit(PROBE_ID1, CLASS_NAME, "siblingProcess1", null);
    LogProbe probe2 = createMethodProbeAtExit(PROBE_ID2, CLASS_NAME, "siblingProcess2", null);
    LogProbe probe3 = createMethodProbeAtExit(PROBE_ID3, CLASS_NAME, "siblingProcess3", null);
    TestSnapshotListener listener = installProbes(probe1, probe2, probe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.onClass(testClass).call("main", "sibling").get();
    return listener;
  }

  private TestSnapshotListener coordinatedSamplingLogTemplate()
      throws IOException, URISyntaxException {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot21";
    LogProbe probe1 =
        createProbeBuilder(PROBE_ID1)
            .where(CLASS_NAME, "process1")
            .template("arg={arg}", parseTemplate("arg={arg}"))
            .captureSnapshot(false)
            .build();
    LogProbe probe2 =
        createProbeBuilder(PROBE_ID2)
            .where(CLASS_NAME, "process2")
            .template("arg={arg}", parseTemplate("arg={arg}"))
            .captureSnapshot(false)
            .build();
    LogProbe probe3 =
        createProbeBuilder(PROBE_ID3)
            .where(CLASS_NAME, "process3")
            .template("arg={arg}", parseTemplate("arg={arg}"))
            .captureSnapshot(false)
            .build();
    TestSnapshotListener listener = installProbes(probe1, probe2, probe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.onClass(testClass).call("main", "1").get();
    return listener;
  }

  private TestSnapshotListener doCoordinatedSamplingTest(TestListenerMethod testRun, int numSamples)
      throws IOException, URISyntaxException {
    MockSampler probeSampler = new MockSampler(numSamples);
    ProbeRateLimiter.setSamplerSupplier(rate -> probeSampler);
    try {
      return testRun.run();
    } finally {
      ProbeRateLimiter.setSamplerSupplier(null);
    }
  }
}
