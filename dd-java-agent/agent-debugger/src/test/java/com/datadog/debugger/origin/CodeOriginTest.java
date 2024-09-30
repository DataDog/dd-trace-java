package com.datadog.debugger.origin;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.REMOTE_CONFIG;
import static com.datadog.debugger.util.TestHelper.setFieldInConfig;
import static datadog.trace.api.DDTags.DD_STACK_CODE_ORIGIN_FRAME;
import static datadog.trace.api.DDTags.DD_STACK_CODE_ORIGIN_TYPE;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.agent.ClassesToRetransformFinder;
import com.datadog.debugger.agent.Configuration;
import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.agent.DebuggerAgentHelper;
import com.datadog.debugger.agent.DebuggerTransformer;
import com.datadog.debugger.agent.DebuggerTransformer.InstrumentationListener;
import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.agent.MockSampler;
import com.datadog.debugger.codeorigin.CodeOriginProbeManager;
import com.datadog.debugger.codeorigin.DefaultCodeOriginRecorder;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.util.ClassNameFiltering;
import com.datadog.debugger.util.TestSnapshotListener;
import com.datadog.debugger.util.TestTraceInterceptor;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.core.CoreTracer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.joor.Reflect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CodeOriginTest {
  private final Instrumentation instr = ByteBuddyAgent.install();
  private final TestTraceInterceptor traceInterceptor = new TestTraceInterceptor();
  public static ClassNameFiltering classNameFiltering =
      new ClassNameFiltering(
          new HashSet<>(
              asList(
                  "sun",
                  "org.junit",
                  "java.",
                  "org.gradle",
                  "com.sun",
                  "worker.org.gradle",
                  "datadog",
                  "com.datadog.debugger.probe",
                  "com.datadog.debugger.codeorigin")));

  private CodeOriginProbeManager probeManager;

  private MockSampler probeSampler;

  private MockSampler globalSampler;

  private ConfigurationUpdater configurationUpdater;

  @BeforeEach
  public void before() {
    CoreTracer tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    tracer.addTraceInterceptor(traceInterceptor);
    probeSampler = new MockSampler();
    globalSampler = new MockSampler();

    ProbeRateLimiter.setSamplerSupplier(rate -> rate < 101 ? probeSampler : globalSampler);
    ProbeRateLimiter.setGlobalSnapshotRate(1000);
    // to activate the call to DebuggerContext.handleException
    setFieldInConfig(Config.get(), "debuggerCodeOriginEnabled", true);
    setFieldInInstrumenterConfig(InstrumenterConfig.get(), "codeOriginEnabled", true);
  }

  private TestSnapshotListener setupCodeOrigin(Config config) {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    configurationUpdater =
        new ConfigurationUpdater(
            instr,
            this::createTransformer,
            config,
            new DebuggerSink(config, probeStatusSink),
            new ClassesToRetransformFinder());
    probeManager = new CodeOriginProbeManager(configurationUpdater, classNameFiltering);
    TestSnapshotListener listener = new TestSnapshotListener(config, probeStatusSink);
    DebuggerAgentHelper.injectSink(listener);
    DebuggerContext.initProbeResolver(configurationUpdater);
    DebuggerContext.initValueSerializer(new JsonSnapshotSerializer());
    DebuggerContext.initCodeOrigin(new DefaultCodeOriginRecorder(probeManager));
    configurationUpdater.accept(REMOTE_CONFIG, null);
    return listener;
  }

  @Test
  public void basicInstrumentation() throws Exception {
    Config config = createConfig();
    TestSnapshotListener listener = setupCodeOrigin(config);
    final String CLASS_NAME = "com.datadog.debugger.CodeOrigin01";
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "fullTrace").get();
    assertEquals(0, result);
    Collection<CodeOriginProbe> probes = probeManager.getProbes();
    assertEquals(2, probes.size());
    assertEquals(0, listener.snapshots.size());
    List<? extends MutableSpan> spans = traceInterceptor.getAllTraces().get(0);
    assertEquals(3, spans.size());
    assertEquals("main", spans.get(2).getLocalRootSpan().getOperationName());

    List<? extends MutableSpan> list =
        spans.stream()
            .filter(span -> !span.getOperationName().equals("exit"))
            .collect(Collectors.toList());

    for (MutableSpan span : list) {
      checkEntrySpanTags(span, false);
    }
    Optional<? extends MutableSpan> exit =
        spans.stream().filter(span -> span.getOperationName().equals("exit")).findFirst();
    assertTrue(exit.isPresent());
    exit.ifPresent(span -> checkExitSpanTags(span, false));
  }

  @Test
  public void postInstrumentation() throws Exception {
    Config config = createConfig();
    TestSnapshotListener listener = setupCodeOrigin(config);
    final String CLASS_NAME = "com.datadog.debugger.CodeOrigin01";
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.onClass(testClass).call("main", "fullTrace").get();
    waitForInstrumentation();

    int result = Reflect.onClass(testClass).call("main", "debug_1").get();
    assertEquals(0, result);
    Collection<CodeOriginProbe> probes = probeManager.getProbes();
    assertEquals(2, probes.size());
    assertEquals(2, listener.snapshots.size());
    List<? extends MutableSpan> spans = traceInterceptor.getTrace();
    assertEquals(3, spans.size());
    assertEquals("main", spans.get(2).getLocalRootSpan().getOperationName());

    List<? extends MutableSpan> list =
        spans.stream()
            .filter(span -> !span.getOperationName().equals("exit"))
            .collect(Collectors.toList());

    for (MutableSpan span : list) {
      checkEntrySpanTags(span, true);
    }
    Optional<? extends MutableSpan> exit =
        spans.stream().filter(span -> span.getOperationName().equals("exit")).findFirst();
    assertTrue(exit.isPresent());
    exit.ifPresent(span -> checkExitSpanTags(span, true));
  }

  private void waitForInstrumentation() {
    long end = System.currentTimeMillis() + 30_000;
    try {
      while (System.currentTimeMillis() < end) {
        if (probeManager.getProbes().size() == 2) {
          Thread.sleep(1000);
          return;
        }

        Thread.sleep(500);
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    throw new IllegalStateException("Some probes failed to instrument");
  }

  private static void checkEntrySpanTags(MutableSpan span, boolean includeSnapshot) {
    String keys = format("Existing keys for %s: %s", span.getOperationName(), ldKeys(span));

    assertEquals(span.getTag(DD_STACK_CODE_ORIGIN_TYPE), "entry", keys);
    assertKeyPresent(span, DD_STACK_CODE_ORIGIN_TYPE);
    assertKeyPresent(span, format(DD_STACK_CODE_ORIGIN_FRAME, 0, "file"));
    assertKeyPresent(span, format(DD_STACK_CODE_ORIGIN_FRAME, 0, "line"));
    assertKeyPresent(span, format(DD_STACK_CODE_ORIGIN_FRAME, 0, "method"));
    assertKeyPresent(span, format(DD_STACK_CODE_ORIGIN_FRAME, 0, "signature"));
    assertKeyPresent(span, format(DD_STACK_CODE_ORIGIN_FRAME, 0, "type"));

    if (includeSnapshot) {
      assertKeyPresent(span, format(DD_STACK_CODE_ORIGIN_FRAME, 0, "snapshot_id"));
    }
  }

  private static void assertKeyPresent(MutableSpan span, String key) {
    assertNotNull(
        span.getTag(key),
        format(
            "'%s' key missing in '%s' span.  Existing LD keys: %s",
            key, span.getOperationName(), ldKeys(span)));
  }

  private static void assertKeyNotPresent(MutableSpan span, String key) {
    assertNull(
        span.getTag(key),
        format(
            "'%s' key missing in '%s' span.  Existing LD keys: %s",
            key, span.getOperationName(), ldKeys(span)));
  }

  private static void checkExitSpanTags(MutableSpan span, boolean includeSnapshot) {
    String keys =
        format("Existing keys for %s: %s", span.getOperationName(), new TreeSet<>(ldKeys(span)));

    assertKeyPresent(span, DD_STACK_CODE_ORIGIN_TYPE);
    assertKeyPresent(span, format(DD_STACK_CODE_ORIGIN_FRAME, 0, "file"));
    assertKeyPresent(span, format(DD_STACK_CODE_ORIGIN_FRAME, 0, "line"));
    assertKeyPresent(span, format(DD_STACK_CODE_ORIGIN_FRAME, 0, "method"));
    assertKeyPresent(span, format(DD_STACK_CODE_ORIGIN_FRAME, 0, "type"));
    if (includeSnapshot) {
      assertKeyPresent(span, format(DD_STACK_CODE_ORIGIN_FRAME, 0, "snapshot_id"));
    }

    MutableSpan rootSpan = span.getLocalRootSpan();
    assertEquals(rootSpan.getTag(DD_STACK_CODE_ORIGIN_TYPE), "entry", keys);
    assertNotNull(rootSpan.getTag(format(DD_STACK_CODE_ORIGIN_FRAME, 1, "file")));
    assertNotNull(rootSpan.getTag(format(DD_STACK_CODE_ORIGIN_FRAME, 1, "line")));
    assertNotNull(rootSpan.getTag(format(DD_STACK_CODE_ORIGIN_FRAME, 1, "method")));
    assertNotNull(rootSpan.getTag(format(DD_STACK_CODE_ORIGIN_FRAME, 1, "type")));
  }

  private static Set<String> ldKeys(MutableSpan span) {
    return span.getTags().keySet().stream()
        .filter(key -> key.startsWith("_dd.ld") || key.startsWith("_dd.stack"))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Config createConfig() {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    when(config.isDebuggerVerifyByteCode()).thenReturn(true);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    when(config.getDebuggerUploadBatchSize()).thenReturn(100);
    return config;
  }

  private DebuggerTransformer createTransformer(
      Config config,
      Configuration configuration,
      InstrumentationListener listener,
      DebuggerSink debuggerSink) {
    return new DebuggerTransformer(config, configuration, listener, debuggerSink);
  }

  private void setFieldInInstrumenterConfig(
      InstrumenterConfig config, String fieldName, Object value) {
    try {
      Field field = config.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(config, value);
    } catch (Throwable e) {
      e.printStackTrace();
    }
  }
}
