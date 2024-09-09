package com.datadog.debugger.origin;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.REMOTE_CONFIG;
import static com.datadog.debugger.util.TestHelper.setFieldInConfig;
import static datadog.trace.api.DDTags.DD_STACK_CODE_ORIGIN_FRAME;
import static datadog.trace.api.DDTags.DD_STACK_CODE_ORIGIN_TYPE;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
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
import datadog.trace.api.DDTags;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerContext.CodeOriginRecorder;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.SpanBuilder;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.core.CoreTracer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.joor.Reflect;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CodeOriginTest {

  private static final List<String> CODE_ORIGIN_TAGS =
      asList(
          DDTags.DD_CODE_ORIGIN_FILE,
          DDTags.DD_CODE_ORIGIN_LINE,
          DDTags.DD_CODE_ORIGIN_METHOD,
          DDTags.DD_CODE_ORIGIN_METHOD_SIGNATURE);
  private static final List<String> STACK_FRAME_TAGS =
      asList(
          format(DD_STACK_CODE_ORIGIN_FRAME, 0, 0, "file"),
          format(DD_STACK_CODE_ORIGIN_FRAME, 0, 0, "line"),
          format(DD_STACK_CODE_ORIGIN_FRAME, 0, 0, "method"),
          format(DD_STACK_CODE_ORIGIN_FRAME, 0, 0, "type"));
  private static final List<String> COMBO_TAGS = new ArrayList<>();

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

  private TracerAPI tracerAPI;

  static {
    COMBO_TAGS.addAll(CODE_ORIGIN_TAGS);
    COMBO_TAGS.addAll(STACK_FRAME_TAGS);

    System.out.println("****** CodeOriginTest.static initializer COMBO_TAGS = " + COMBO_TAGS);
  }

  @Test
  public void testParents() {
    AgentSpan top = newSpan("root");

    try (AgentScope ignored = tracerAPI.activateSpan(top, ScopeSource.MANUAL)) {
      AgentSpan entry = newSpan("entry");

      assertEquals("root", top.getLocalRootSpan().getOperationName());
      assertEquals("root", entry.getLocalRootSpan().getOperationName());
      Assert.assertNotEquals(entry, entry.getLocalRootSpan());
    }
  }

  private AgentSpan newSpan(String name) {
    tracerAPI = AgentTracer.get();
    SpanBuilder span = tracerAPI.buildSpan("code origin tests", name);
    if (tracerAPI.activeSpan() != null) {
      span.asChildOf(tracerAPI.activeSpan().context());
    }
    return span.start();
  }

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
    CodeOriginRecorder originRecorder = new DefaultCodeOriginRecorder(probeManager);
    DebuggerContext.initCodeOrigin(originRecorder);
    configurationUpdater.accept(REMOTE_CONFIG, null);
    return listener;
  }

  @Test
  public void onlyInstrument() throws Exception {
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
      checkForTags(span, "entry", COMBO_TAGS, emptyList());
    }
    Optional<? extends MutableSpan> exit =
        spans.stream().filter(span -> span.getOperationName().equals("exit")).findFirst();
    assertTrue(exit.isPresent());
    exit.ifPresent(CodeOriginTest::checkExitSpanTags);
  }

  private static void checkForTags(
      MutableSpan span, String spanType, List<String> included, List<String> excluded) {
    String keys =
        format(
            "Existing keys for %s: %s",
            span.getOperationName(), new TreeSet<>(span.getTags().keySet()));
    assertEquals(span.getTag(format(DD_STACK_CODE_ORIGIN_TYPE, 0)), spanType, keys);
    for (String tag : included) {
      assertNotNull(span.getTag(tag), tag + " not found.  " + keys);
    }
    for (String tag : excluded) {
      assertNull(span.getTag(tag), tag + " not found.  " + keys);
    }
  }

  private static void checkExitSpanTags(MutableSpan span) {
    String keys =
        format(
            "Existing keys for %s: %s",
            span.getOperationName(), new TreeSet<>(span.getTags().keySet()));

    assertNull(span.getTag(DDTags.DD_CODE_ORIGIN_FILE), keys);
    assertNull(span.getTag(DDTags.DD_CODE_ORIGIN_LINE), keys);
    assertNull(span.getTag(DDTags.DD_CODE_ORIGIN_METHOD), keys);
    assertNull(span.getTag(DDTags.DD_CODE_ORIGIN_METHOD_SIGNATURE), keys);

    assertNull(span.getTag(format(DD_STACK_CODE_ORIGIN_TYPE, 1)), keys);
    assertNull(span.getTag(format(DD_STACK_CODE_ORIGIN_FRAME, 1, 0, "file")));
    assertNull(span.getTag(format(DD_STACK_CODE_ORIGIN_FRAME, 1, 0, "line")));
    assertNull(span.getTag(format(DD_STACK_CODE_ORIGIN_FRAME, 1, 0, "method")));
    assertNull(span.getTag(format(DD_STACK_CODE_ORIGIN_FRAME, 1, 0, "type")));
  }

  public static Config createConfig() {
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

  public void setFieldInInstrumenterConfig(
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
