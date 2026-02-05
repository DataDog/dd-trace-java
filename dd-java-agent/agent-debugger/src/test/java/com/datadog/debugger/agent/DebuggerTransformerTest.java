package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static utils.TestClassFileHelper.getClassFileBytes;

import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.util.TestSnapshotListener;
import datadog.trace.api.Config;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import freemarker.template.Template;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.VarInsnNode;

public class DebuggerTransformerTest {
  private static final String LANGUAGE = "java";
  private static final ProbeId PROBE_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final String SERVICE_NAME = "service-name";

  enum InstrumentationKind {
    ENTRY_EXIT,
    LINE
  }

  enum ExceptionKind {
    NONE,
    UNHANDLED,
    HANDLED
  }

  static final String VAR_NAME = "var";
  static final String SCOPED_VAR_NAME = "scoped";
  static final String SCOPED_VAR_TYPE = "int";
  static final Object SCOPED_VAR_VALUE = 10;

  private static Instrumentation instr;
  private static Template classTemplate;

  private static Tracer noopTracer;

  private static final CapturedContext.CapturedValue[] CORRELATION_FIELDS =
      new CapturedContext.CapturedValue[2];

  @BeforeAll
  static void setupAll() throws Exception {
    // disable tracer integration
    System.setProperty("dd." + TraceInstrumentationConfig.TRACE_ENABLED, "false");

    // setup the tracer
    noopTracer = GlobalTracer.get();
    Tracer mockTracer = mock(Tracer.class);
    when(mockTracer.getTraceId()).thenReturn("1");
    when(mockTracer.getSpanId()).thenReturn("2");
    GlobalTracer.forceRegister(mockTracer);

    // prepare the correlation fields golden muster
    CORRELATION_FIELDS[0] =
        CapturedContext.CapturedValue.of(
            "dd.trace_id", "java.lang.String", mockTracer.getTraceId());
    CORRELATION_FIELDS[1] =
        CapturedContext.CapturedValue.of("dd.span_id", "java.lang.String", mockTracer.getSpanId());

    instr = ByteBuddyAgent.install();
    freemarker.template.Configuration cfg =
        new freemarker.template.Configuration(freemarker.template.Configuration.VERSION_2_3_29);
    cfg.setBooleanFormat("c");
    classTemplate =
        new Template(
            "classTemplate",
            new InputStreamReader(
                DebuggerTransformerTest.class.getResourceAsStream("/TargetClass.ftlh")),
            cfg);
    // TODO asserts are operating on 'toString()' which requires keeping the underlying object so we
    // just disable serialization for now
    DebuggerContext.initValueSerializer(null);
  }

  @AfterEach
  void tearDown() {
    // disable tracer integration
    System.setProperty("dd." + TraceInstrumentationConfig.TRACE_ENABLED, "false");
    ProbeRateLimiter.resetGlobalRate();
  }

  @BeforeEach
  void setup() {
    DebuggerContext.initProbeResolver(null);
  }

  @Test
  public void testDump() {
    Config config = createConfig();
    Path initialTmpDir = DebuggerTransformer.DUMP_PATH;
    DebuggerTransformer.DUMP_PATH = Paths.get("/tmp/debugger");
    try {
      when(config.isDynamicInstrumentationClassFileDumpEnabled()).thenReturn(true);
      File instrumentedClassFile = new File("/tmp/debugger/java.util.ArrayList.class");
      File origClassFile = new File("/tmp/debugger/java.util.ArrayList_orig.class");
      if (instrumentedClassFile.exists()) {
        instrumentedClassFile.delete();
      }
      if (origClassFile.exists()) {
        origClassFile.delete();
      }
      LogProbe logProbe =
          LogProbe.builder().where("java.util.ArrayList", "add").probeId("", 0).build();
      DebuggerTransformer debuggerTransformer =
          new DebuggerTransformer(
              config,
              new ProbeMetadata(),
              new Configuration(SERVICE_NAME, Collections.singletonList(logProbe)));
      debuggerTransformer.transform(
          ClassLoader.getSystemClassLoader(),
          "java.util.ArrayList",
          ArrayList.class,
          null,
          getClassFileBytes(ArrayList.class));
      assertTrue(instrumentedClassFile.exists());
      assertTrue(origClassFile.exists());
      assertTrue(instrumentedClassFile.delete());
      assertTrue(origClassFile.delete());
    } finally {
      DebuggerTransformer.DUMP_PATH = initialTmpDir;
    }
  }

  @Test
  public void testMultiProbes() {
    doTestMultiProbes(
        Class::getName,
        new ProbeTestInfo(ArrayList.class, "add"),
        new ProbeTestInfo(HashMap.class, "<init>", "void ()"));
  }

  @Test
  public void testMultiProbesSimpleName() {
    doTestMultiProbes(
        Class::getSimpleName,
        new ProbeTestInfo(ArrayList.class, "add"),
        new ProbeTestInfo(HashMap.class, "<init>", "void ()"));
  }

  private void doTestMultiProbes(
      Function<Class<?>, String> getClassName, ProbeTestInfo... probeInfos) {
    Config config = createConfig();
    List<LogProbe> logProbes = new ArrayList<>();
    for (ProbeTestInfo probeInfo : probeInfos) {
      String className = getClassName.apply(probeInfo.clazz);
      LogProbe logProbe =
          LogProbe.builder()
              .where(className, probeInfo.methodName, probeInfo.signature)
              .probeId("", 0)
              .build();
      logProbes.add(logProbe);
    }
    Configuration configuration = new Configuration(SERVICE_NAME, logProbes);
    DebuggerTransformer debuggerTransformer =
        new DebuggerTransformer(config, new ProbeMetadata(), configuration);
    for (ProbeTestInfo probeInfo : probeInfos) {
      byte[] newClassBuffer =
          debuggerTransformer.transform(
              ClassLoader.getSystemClassLoader(),
              probeInfo.clazz.getName(), // always FQN
              probeInfo.clazz,
              null,
              getClassFileBytes(probeInfo.clazz));
      Assertions.assertNotNull(newClassBuffer);
    }
    byte[] newClassBuffer =
        debuggerTransformer.transform(
            ClassLoader.getSystemClassLoader(),
            "java.util.HashSet",
            HashSet.class,
            null,
            getClassFileBytes(HashSet.class));
    assertNull(newClassBuffer);
  }

  static class ProbeTestInfo {
    final Class<?> clazz;
    final String methodName;
    final String signature;

    public ProbeTestInfo(Class<?> clazz, String methodName) {
      this(clazz, methodName, null);
    }

    public ProbeTestInfo(Class<?> clazz, String methodName, String signature) {
      this.clazz = clazz;
      this.methodName = methodName;
      this.signature = signature;
    }
  }

  @Test
  public void testBlockedProbes() {
    Config config = createConfig();
    List<LogProbe> logProbes =
        Arrays.asList(
            LogProbe.builder()
                .language(LANGUAGE)
                .probeId(PROBE_ID)
                .where("java.lang.String", "toString")
                .build());
    Configuration configuration = new Configuration(SERVICE_NAME, logProbes);
    AtomicReference<InstrumentationResult> lastResult = new AtomicReference<>(null);
    DebuggerTransformer debuggerTransformer =
        new DebuggerTransformer(
            config,
            configuration,
            ((definition, result) -> lastResult.set(result)),
            new ProbeMetadata(),
            new DebuggerSink(
                config, new ProbeStatusSink(config, config.getFinalDebuggerSnapshotUrl(), false)));
    byte[] newClassBuffer =
        debuggerTransformer.transform(
            ClassLoader.getSystemClassLoader(),
            "java.lang.String",
            String.class,
            null,
            getClassFileBytes(String.class));
    assertNull(newClassBuffer);
    Assertions.assertNotNull(lastResult.get());
    assertTrue(lastResult.get().isBlocked());
    Assertions.assertFalse(lastResult.get().isInstalled());
    assertEquals("java.lang.String", lastResult.get().getTypeName());
  }

  @Test
  public void classBeingRedefinedNull() {
    Config config = createConfig();
    LogProbe logProbe = LogProbe.builder().where("ArrayList", "add").probeId("", 0).build();
    Configuration configuration =
        new Configuration(SERVICE_NAME, Collections.singletonList(logProbe));
    AtomicReference<InstrumentationResult> lastResult = new AtomicReference<>(null);
    DebuggerTransformer debuggerTransformer =
        new DebuggerTransformer(
            config,
            configuration,
            ((definition, result) -> lastResult.set(result)),
            new ProbeMetadata(),
            new DebuggerSink(
                config, new ProbeStatusSink(config, config.getFinalDebuggerSnapshotUrl(), false)));
    byte[] newClassBuffer =
        debuggerTransformer.transform(
            ClassLoader.getSystemClassLoader(),
            "java.util.ArrayList",
            null, // classBeingRedefined
            null,
            getClassFileBytes(ArrayList.class));
    Assertions.assertNotNull(newClassBuffer);
    Assertions.assertNotNull(lastResult.get());
    Assertions.assertFalse(lastResult.get().isBlocked());
    assertTrue(lastResult.get().isInstalled());
    assertEquals("java.util.ArrayList", lastResult.get().getTypeName());
  }

  @Test
  public void classGenerationFailed() {
    Config config = createConfig();
    final String CLASS_NAME = DebuggerAgent.class.getTypeName();
    final String METHOD_NAME = "run";
    MockProbe mockProbe = MockProbe.builder(PROBE_ID).where(CLASS_NAME, METHOD_NAME).build();
    LogProbe logProbe1 =
        LogProbe.builder().probeId("logprobe1", 0).where(CLASS_NAME, METHOD_NAME).build();
    LogProbe logProbe2 =
        LogProbe.builder().probeId("logprobe2", 0).where(CLASS_NAME, METHOD_NAME).build();
    Configuration configuration =
        Configuration.builder()
            .setService(SERVICE_NAME)
            .add(mockProbe, logProbe1, logProbe2)
            .build();
    AtomicReference<InstrumentationResult> lastResult = new AtomicReference<>(null);
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    TestSnapshotListener listener = new TestSnapshotListener(config, probeStatusSink);
    DebuggerTransformer debuggerTransformer =
        new DebuggerTransformer(
            config,
            configuration,
            ((definition, result) -> lastResult.set(result)),
            new ProbeMetadata(),
            listener);
    DebuggerAgentHelper.injectSink(listener);
    byte[] newClassBuffer =
        debuggerTransformer.transform(
            ClassLoader.getSystemClassLoader(),
            "com/datadog/debugger/agent/DebuggerAgent",
            null,
            null,
            getClassFileBytes(DebuggerAgent.class));
    assertNull(newClassBuffer);
    ArgumentCaptor<String> strCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ProbeId> probeIdCaptor = ArgumentCaptor.forClass(ProbeId.class);
    verify(probeStatusSink, times(3)).addError(probeIdCaptor.capture(), strCaptor.capture());
    assertEquals("logprobe1", probeIdCaptor.getAllValues().get(0).getId());
    assertEquals("logprobe2", probeIdCaptor.getAllValues().get(1).getId());
    assertEquals(PROBE_ID.getId(), probeIdCaptor.getAllValues().get(2).getId());
    assertTrue(strCaptor.getAllValues().get(0).startsWith("Instrumentation failed for "
            + CLASS_NAME
            + ": java.lang.ArrayIndexOutOfBoundsException:"));
    assertTrue(strCaptor.getAllValues().get(1).startsWith("Instrumentation failed for "
        + CLASS_NAME
        + ": java.lang.ArrayIndexOutOfBoundsException:"));
    assertTrue(strCaptor.getAllValues().get(2).startsWith("Instrumentation failed for "
        + CLASS_NAME
        + ": java.lang.ArrayIndexOutOfBoundsException:"));
  }

  @Test
  public void ordering() {
    Config config = createConfig();
    List<ProbeDefinition> invocationOrder = new ArrayList<>();
    MetricProbe metricProbe = createMock(MetricProbe.class, invocationOrder, "metric");
    LogProbe logProbe = createMock(LogProbe.class, invocationOrder, "log");
    SpanProbe spanProbe = createMock(SpanProbe.class, invocationOrder, "span");
    SpanDecorationProbe spanDecorationProbe =
        createMock(SpanDecorationProbe.class, invocationOrder, "spanDecoration");
    Configuration configuration =
        Configuration.builder()
            .add(spanDecorationProbe)
            .add(spanProbe)
            .add(metricProbe)
            .add(logProbe)
            .build();
    DebuggerTransformer debuggerTransformer =
        new DebuggerTransformer(
            config,
            configuration,
            (definition, result) -> {
              if (result.isInstalled()) {
                invocationOrder.add(definition);
              }
            },
            new ProbeMetadata(),
            new DebuggerSink(
                config, new ProbeStatusSink(config, config.getFinalDebuggerSnapshotUrl(), false)));
    debuggerTransformer.transform(
        ClassLoader.getSystemClassLoader(),
        ArrayList.class.getName(), // always FQN
        ArrayList.class,
        null,
        getClassFileBytes(ArrayList.class));
    assertEquals(4, invocationOrder.size());
    assertEquals(metricProbe, invocationOrder.get(0));
    assertEquals(logProbe, invocationOrder.get(1));
    assertEquals(spanProbe, invocationOrder.get(2));
    assertEquals(spanDecorationProbe, invocationOrder.get(3));
  }

  <T extends ProbeDefinition> T createMock(
      Class<T> clazz, List<ProbeDefinition> invocationOrder, String id) {
    ProbeDefinition mock = mock(clazz);
    doAnswer(
            invocation -> {
              return InstrumentationResult.Status.INSTALLED;
            })
        .when(mock)
        .instrument(any(), anyList(), anyList());
    when(mock.getProbeId()).thenReturn(new ProbeId(id, 0));
    Where where = Where.of(ArrayList.class.getName(), "add", "(Object)");
    when(mock.getWhere()).thenReturn(where);
    return (T) mock;
  }

  private Config createConfig() {
    Config config = mock(Config.class);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    when(config.getDynamicInstrumentationUploadBatchSize()).thenReturn(100);
    return config;
  }

  private static class MockProbe extends SpanProbe {

    public MockProbe(ProbeId probeId, Where where) {
      super(LANGUAGE, probeId, null, where);
    }

    @Override
    public InstrumentationResult.Status instrument(
        MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<Integer> probeIndices) {
      methodInfo
          .getMethodNode()
          .instructions
          .insert(
              new VarInsnNode(Opcodes.ASTORE, methodInfo.getMethodNode().localVariables.size()));
      return InstrumentationResult.Status.INSTALLED;
    }

    public static MockProbe.Builder builder(ProbeId probeId) {
      return new MockProbe.Builder().probeId(probeId);
    }

    public static class Builder extends ProbeDefinition.Builder<MockProbe.Builder> {
      public MockProbe build() {
        return new MockProbe(probeId, where);
      }
    }
  }
}
