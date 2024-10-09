package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.SpanDecorationProbeInstrumentationTest.resolver;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.agent.Configuration.Builder;
import com.datadog.debugger.codeorigin.CodeOriginProbeManager;
import com.datadog.debugger.codeorigin.DefaultCodeOriginRecorder;
import com.datadog.debugger.origin.CodeOriginTest;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.TriggerProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.util.TestTraceInterceptor;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Config;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.util.Redaction;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.joor.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.mockito.ArgumentMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TestInstance(Lifecycle.PER_CLASS)
public class TriggerProbeInstrumentationTest extends ProbeInstrumentationTest {
  private static final ProbeId DEBUG_PROBE_ID = new ProbeId("86753098675309", 0);

  private static final ProbeId CODE_ORIGIN_PROBE_ID = new ProbeId("48946084894608", 0);

  private static final Logger log = LoggerFactory.getLogger(TriggerProbeInstrumentationTest.class);

  public static final CodeOriginProbeManager probeManager =
      new CodeOriginProbeManager(
          mock(ConfigurationUpdater.class), CodeOriginTest.classNameFiltering);

  private DefaultCodeOriginRecorder codeOriginRecorder =
      new DefaultCodeOriginRecorder(probeManager);

  private TestTraceInterceptor traceInterceptor;

  @BeforeEach
  public void setUp() {
    traceInterceptor = new TestTraceInterceptor();
    CoreTracer tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    tracer.addTraceInterceptor(traceInterceptor);
  }

  @Override
  @AfterEach
  public void after() {
    super.after();
    Redaction.clearUserDefinedTypes();
  }

  @Test
  public void testProbeInstallation() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    installProbe(CLASS_NAME, "process", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
    result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals(
        DEBUG_PROBE_ID.getId(),
        span.getTags().get("_dd.ld.probe_id"),
        span.getTags().keySet().toString());
    String debugFlag =
        ((DDSpan) span.getLocalRootSpan()).context().getPropagationTags().getDebugPropagation();
    assertEquals("1", debugFlag);
  }

  @Test
  public void testWithCodeOrigin() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";

    Where where = Where.of(CLASS_NAME, "process", "int (java.lang.String)", (String[]) null);
    installProbes(
        TriggerProbe.builder()
            .probeId(DEBUG_PROBE_ID)
            .where(CLASS_NAME, "process", "int (java.lang.String)")
            .build(),
        new CodeOriginProbe(CODE_ORIGIN_PROBE_ID, "int (java.lang.String)", where, probeManager));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals(
        DEBUG_PROBE_ID.getId(),
        span.getTags().get("_dd.ld.probe_id"),
        span.getTags().keySet().toString());
    String debugFlag =
        ((DDSpan) span.getLocalRootSpan()).context().getPropagationTags().getDebugPropagation();
    assertEquals("1", debugFlag);
    verify(probeStatusSink).addEmitting(ArgumentMatchers.eq(DEBUG_PROBE_ID));
  }

  private void installProbe(String typeName, String methodName, String signature) {
    TriggerProbe probe =
        TriggerProbe.builder()
            .probeId(DEBUG_PROBE_ID)
            .where(typeName, methodName, signature)
            .build();
    registerConfiguration(Configuration.builder().setService(SERVICE_NAME).add(probe).build());
  }

  @SuppressWarnings({"unchecked"})
  private <D extends ProbeDefinition> void installProbes(D... definitions) {
    Builder builder = Configuration.builder().setService(SERVICE_NAME);
    bucketProbes(definitions, builder);
    registerConfiguration(builder.build());
  }

  @SuppressWarnings("unchecked")
  private static <D extends ProbeDefinition> void bucketProbes(D[] definitions, Builder builder) {
    Arrays.stream(definitions)
        .collect(Collectors.groupingBy(d -> d.getClass().getSimpleName()))
        .forEach(
            (key, value1) -> {
              switch (key) {
                case ("CodeOriginProbe"):
                  value1.forEach(probe -> probeManager.installProbe((CodeOriginProbe) probe));
                  break;
                case ("MetricProbe"):
                  builder.addMetricProbes((List<MetricProbe>) value1);
                  break;
                case ("TriggerProbe"):
                  builder.addTriggerProbes((List<TriggerProbe>) value1);
                  break;
                case ("LogProbe"):
                  builder.addLogProbes((List<LogProbe>) value1);
                  break;
                default:
                  throw new UnsupportedOperationException(key + " is an unknown probe type");
              }
            });
  }

  private void registerConfiguration(Configuration configuration) {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    when(config.isDebuggerCodeOriginEnabled()).thenReturn(true);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    probeStatusSink = mock(ProbeStatusSink.class);
    currentTransformer =
        new DebuggerTransformer(
            config, configuration, null, new DebuggerSink(config, probeStatusSink));
    instr.addTransformer(currentTransformer);
    mockSink = new MockSink(config, probeStatusSink);
    System.out.println("remocking mockSink");
    DebuggerAgentHelper.injectSink(mockSink);
    DebuggerContext.initProbeResolver((encodedProbeId) -> resolver(encodedProbeId, configuration));
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    DebuggerContext.initCodeOrigin(codeOriginRecorder);
  }
}
