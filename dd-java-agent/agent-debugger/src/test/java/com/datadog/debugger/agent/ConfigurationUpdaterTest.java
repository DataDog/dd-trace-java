package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.REMOTE_CONFIG;
import static com.datadog.debugger.agent.DebuggerProductChangesListener.LOG_PROBE_PREFIX;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ConfigurationUpdaterTest {
  private static final String LANGUAGE = "java";
  private static final ProbeId PROBE_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 42);
  private static final ProbeId PROBE_ID2 = new ProbeId("beae1808-f3b0-4ea8-a74f-826790c5e6f8", 12);
  private static final ProbeId METRIC_ID = new ProbeId("cfbf2918-e4c1-5fb9-b85e-937881d6f7e9", 1);
  private static final ProbeId METRIC_ID2 = new ProbeId("cfbf2919-e4c1-5fb9-b85e-937881d6f7e9", 5);
  private static final ProbeId LOG_ID = new ProbeId("d0c03a2a-f5d2-60ca-c96f-a48992e708fa", 2);
  private static final ProbeId LOG_ID2 = new ProbeId("d0c03a2b-f5d2-60ca-c96f-a48992e708fa", 6);
  private static final ProbeId SPAN_ID = new ProbeId("cfbf2918-e4c1-5fb9-b85e-937881d6f7e9", 1);
  private static final ProbeId SPAN_DECORATION_ID =
      new ProbeId("cfbf2918-e4c1-5fb9-b85e-937881d6f7e9", 1);

  @Mock private Instrumentation inst;
  @Mock private DebuggerTransformer transformer;
  @Mock private Config tracerConfig;
  @Mock private DebuggerSink debuggerSink;
  @Mock private ProbeStatusSink probeStatusSink;

  private DebuggerSink debuggerSinkWithMockStatusSink;

  @BeforeEach
  void setUp() {
    lenient().when(tracerConfig.getFinalDebuggerSnapshotUrl()).thenReturn("http://localhost");
    lenient().when(tracerConfig.getDynamicInstrumentationUploadBatchSize()).thenReturn(100);
    lenient().when(tracerConfig.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost");

    debuggerSinkWithMockStatusSink = new DebuggerSink(tracerConfig, probeStatusSink);
  }

  @Test
  public void acceptNoProbes() {
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    configurationUpdater.accept(REMOTE_CONFIG, null);
    verify(inst, never()).addTransformer(any(), eq(true));
    verifyNoInteractions(debuggerSink);
  }

  @Test
  public void acceptNewProbe() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSinkWithMockStatusSink);
    List<LogProbe> logProbes =
        singletonList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID.getEncodedId()));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
  }

  @Test
  public void acceptNewMultiProbes() throws UnmodifiableClassException {
    doTestAcceptMultiProbes(Class::getName, String.class, HashMap.class);
  }

  @Test
  public void acceptNewMultiProbesSimpleName() throws UnmodifiableClassException {
    doTestAcceptMultiProbes(Class::getSimpleName, String.class, HashMap.class);
  }

  private void doTestAcceptMultiProbes(Function<Class<?>, String> getClassName, Class<?>... classes)
      throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(classes);
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSinkWithMockStatusSink);
    List<LogProbe> logProbes =
        Arrays.stream(classes)
            .map(getClassName)
            .map(className -> LogProbe.builder().probeId(PROBE_ID).where(className, "foo").build())
            .collect(Collectors.toList());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    for (Class<?> expectedClass : classes) {
      verify(inst).retransformClasses(eq(expectedClass));
    }
    verify(probeStatusSink, times(2)).addReceived(eq(PROBE_ID));
  }

  @Test
  public void acceptDuplicatedProbes() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSinkWithMockStatusSink);
    List<LogProbe> logProbes =
        Arrays.asList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build(),
            LogProbe.builder().probeId(PROBE_ID2).where("java.lang.String", "concat").build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(2, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID.getEncodedId()));
    assertTrue(appliedDefinitions.containsKey(PROBE_ID2.getEncodedId()));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
  }

  @Test
  public void accept2PhaseDuplicatedProbes() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSinkWithMockStatusSink);
    // phase 1: single probe definition
    List<LogProbe> logProbes =
        Arrays.asList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID.getEncodedId()));
    // phase 2: add duplicated probe definitions
    logProbes =
        Arrays.asList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build(),
            LogProbe.builder().probeId(PROBE_ID2).where("java.lang.String", "concat").build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(2, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID2.getEncodedId()));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
  }

  @Test
  public void acceptRemoveDuplicatedProbes() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSinkWithMockStatusSink);
    // phase 1: duplicated probe definition
    List<LogProbe> logProbes =
        Arrays.asList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build(),
            LogProbe.builder().probeId(PROBE_ID2).where("java.lang.String", "concat").build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(2, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID.getEncodedId()));
    assertTrue(appliedDefinitions.containsKey(PROBE_ID2.getEncodedId()));
    // phase 2: remove duplicated probe definitions
    logProbes =
        Arrays.asList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID.getEncodedId()));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
    verify(probeStatusSink).removeDiagnostics(eq(PROBE_ID2));
  }

  @Test
  public void acceptDontDedupMetricProbes() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSinkWithMockStatusSink);
    List<LogProbe> logProbes =
        Arrays.asList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    List<MetricProbe> metricProbes =
        Arrays.asList(
            MetricProbe.builder().probeId(METRIC_ID).where("java.lang.String", "concat").build());
    List<ProbeDefinition> definitions = new ArrayList<>(metricProbes);
    definitions.addAll(logProbes);
    configurationUpdater.accept(REMOTE_CONFIG, definitions);
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(2, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID.getEncodedId()));
    assertTrue(appliedDefinitions.containsKey(METRIC_ID.getEncodedId()));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(METRIC_ID));
  }

  @Test
  public void acceptSourceFileLineNumber() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    List<LogProbe> logProbes =
        singletonList(
            LogProbe.builder()
                .probeId(PROBE_ID)
                .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
                .build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    verify(inst).retransformClasses(eq(String.class));
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID.getEncodedId()));
  }

  @Test
  public void acceptSourceFileLineNumberEmptyTypeName() throws UnmodifiableClassException {
    // create anonymous class for dealing with Class#getSimpleName returning empty string for it
    Runnable runnable =
        new Runnable() {
          @Override
          public void run() {}
        };
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class, runnable.getClass()});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    List<LogProbe> logProbes =
        singletonList(
            LogProbe.builder()
                .probeId(PROBE_ID)
                .where("", "", "", 1966, "java/lang/String.java")
                .build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    verify(inst).retransformClasses(eq(String.class));
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID.getEncodedId()));
  }

  @Test
  public void acceptSourceFileLineNumberAnonymousClass() throws UnmodifiableClassException {
    Runnable runnable =
        new Runnable() {
          @Override
          public void run() {}
        };
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class, runnable.getClass()});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    List<LogProbe> logProbes =
        singletonList(
            LogProbe.builder()
                .probeId(PROBE_ID)
                .where("", "", "", 136, "ConfigurationUpdaterTest$2")
                .build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    verify(inst).retransformClasses(eq(runnable.getClass()));
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID.getEncodedId()));
  }

  @Test
  public void acceptDeleteProbe() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class, HashMap.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSinkWithMockStatusSink);
    LogProbe probe1 =
        LogProbe.builder()
            .language(LANGUAGE)
            .probeId(PROBE_ID)
            .where("java.lang.String", "concat")
            .build();
    LogProbe probe2 =
        LogProbe.builder()
            .language(LANGUAGE)
            .probeId(PROBE_ID2)
            .where("java.util.HashMap", "<init>", "void ()")
            .build();
    List<LogProbe> logProbes = Arrays.asList(probe1, probe2);
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
    logProbes = singletonList(probe1);
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(probeStatusSink).removeDiagnostics(eq(PROBE_ID2));
    verify(inst).removeTransformer(any());
    ArgumentCaptor<Class<?>[]> captor = ArgumentCaptor.forClass(Class[].class);
    verify(inst, times(3)).retransformClasses(captor.capture());
    List<Class<?>[]> allValues = captor.getAllValues();
    assertEquals(String.class, allValues.get(0));
    assertEquals(HashMap.class, allValues.get(1));
    assertEquals(HashMap.class, allValues.get(2)); // for removing instrumentation
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    Assertions.assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(probe1.getProbeId().getEncodedId()));
  }

  @Test
  public void acceptDeleteProbeSameClass() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    AtomicInteger expectedDefinitions = new AtomicInteger(2);
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            (tracerConfig, configuration, listener, probeMetadata, debuggerSink) -> {
              assertEquals(expectedDefinitions.get(), configuration.getDefinitions().size());
              return transformer;
            },
            tracerConfig,
            debuggerSinkWithMockStatusSink,
            new ClassesToRetransformFinder());
    LogProbe probe1 =
        LogProbe.builder()
            .language(LANGUAGE)
            .probeId(PROBE_ID)
            .where("java.lang.String", "concat")
            .build();
    LogProbe probe2 =
        LogProbe.builder()
            .language(LANGUAGE)
            .probeId(PROBE_ID2)
            .where("java.lang.String", "indexOf", "int (int)")
            .build();
    List<LogProbe> logProbes = Arrays.asList(probe1, probe2);
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
    logProbes = singletonList(probe1);
    expectedDefinitions.set(1);
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(probeStatusSink).removeDiagnostics(eq(PROBE_ID2));
    verify(inst).removeTransformer(any());
    ArgumentCaptor<Class<?>[]> captor = ArgumentCaptor.forClass(Class[].class);
    verify(inst, times(2)).retransformClasses(captor.capture());
    List<Class<?>[]> allValues = captor.getAllValues();
    assertEquals(String.class, allValues.get(0));
    assertEquals(String.class, allValues.get(1));
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    Assertions.assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(probe1.getProbeId().getEncodedId()));
  }

  @Test
  public void acceptClearProbes() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSinkWithMockStatusSink);
    List<LogProbe> logProbes =
        singletonList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    configurationUpdater.accept(REMOTE_CONFIG, null);
    verify(probeStatusSink).removeDiagnostics(eq(PROBE_ID));
    verify(inst).removeTransformer(any());
    verify(inst, times(2)).retransformClasses(any());
    Assertions.assertEquals(0, configurationUpdater.getAppliedDefinitions().size());
  }

  @Test
  public void resolve() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    List<LogProbe> logProbes =
        singletonList(
            LogProbe.builder()
                .probeId(PROBE_ID)
                .where("java.lang.String", "concat")
                .when(
                    new ProbeCondition(
                        DSL.when(DSL.eq(DSL.ref("arg"), DSL.value("foo"))), "arg == 'foo'"))
                .build());
    logProbes.get(0).buildLocation(null);
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    configurationUpdater.getProbeMetadata().addProbe(logProbes.get(0));
    ProbeImplementation probeImplementation = configurationUpdater.resolve(0);
    Assertions.assertEquals(
        PROBE_ID.getEncodedId(), probeImplementation.getProbeId().getEncodedId());
    Assertions.assertEquals("java.lang.String", probeImplementation.getLocation().getType());
    Assertions.assertEquals("concat", probeImplementation.getLocation().getMethod());
    Assertions.assertNotNull(((LogProbe) probeImplementation).getProbeCondition());
  }

  @Test
  public void resolveFails() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    List<LogProbe> logProbes =
        singletonList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(inst).retransformClasses(eq(String.class));
    // simulate that there is a snapshot probe instrumentation left in HashMap class
    ProbeImplementation probeImplementation = configurationUpdater.resolve(1);
    Assertions.assertNull(probeImplementation);
  }

  @Test
  public void acceptNewMetric() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    List<MetricProbe> metricProbes =
        singletonList(
            MetricProbe.builder().probeId(METRIC_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(REMOTE_CONFIG, metricProbes);
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(METRIC_ID.getEncodedId()));
  }

  @Test
  public void acceptNewLog() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    List<LogProbe> logProbes =
        singletonList(
            LogProbe.builder().probeId(LOG_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(LOG_ID.getEncodedId()));
  }

  @Test
  public void acceptDeleteMetric() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class, HashMap.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    MetricProbe metricProbe1 =
        MetricProbe.builder()
            .language(LANGUAGE)
            .probeId(METRIC_ID)
            .where("java.lang.String", "concat")
            .build();
    MetricProbe metricProbe2 =
        MetricProbe.builder()
            .language(LANGUAGE)
            .probeId(METRIC_ID2)
            .where("java.util.HashMap", "<init>", "void ()")
            .build();
    List<MetricProbe> metricProbes = Arrays.asList(metricProbe1, metricProbe2);
    configurationUpdater.accept(REMOTE_CONFIG, metricProbes);
    metricProbes = singletonList(metricProbe1);
    configurationUpdater.accept(REMOTE_CONFIG, metricProbes);
    verify(inst).removeTransformer(any());
    ArgumentCaptor<Class<?>[]> captor = ArgumentCaptor.forClass(Class[].class);
    verify(inst, times(3)).retransformClasses(captor.capture());
    List<Class<?>[]> allValues = captor.getAllValues();
    assertEquals(String.class, allValues.get(0));
    assertEquals(HashMap.class, allValues.get(1));
    assertEquals(HashMap.class, allValues.get(2)); // for removing instrumentation
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(metricProbe1.getProbeId().getEncodedId()));
  }

  @Test
  public void acceptDeleteLog() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class, HashMap.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    LogProbe logProbe1 =
        LogProbe.builder()
            .language(LANGUAGE)
            .probeId(LOG_ID)
            .where("java.lang.String", "concat")
            .build();
    LogProbe logProbe2 =
        LogProbe.builder()
            .language(LANGUAGE)
            .probeId(LOG_ID2)
            .where("java.util.HashMap", "<init>", "void ()")
            .build();
    List<LogProbe> logProbes = Arrays.asList(logProbe1, logProbe2);
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    logProbes = singletonList(logProbe1);
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    verify(inst).removeTransformer(any());
    ArgumentCaptor<Class<?>[]> captor = ArgumentCaptor.forClass(Class[].class);
    verify(inst, times(3)).retransformClasses(captor.capture());
    List<Class<?>[]> allValues = captor.getAllValues();
    assertEquals(String.class, allValues.get(0));
    assertEquals(HashMap.class, allValues.get(1));
    assertEquals(HashMap.class, allValues.get(2)); // for removing instrumentation
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(logProbe1.getProbeId().getEncodedId()));
  }

  @Test
  public void acceptClearMetrics() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    List<MetricProbe> metricProbes =
        singletonList(
            MetricProbe.builder().probeId(METRIC_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(REMOTE_CONFIG, metricProbes);
    configurationUpdater.accept(REMOTE_CONFIG, null);
    verify(inst).removeTransformer(any());
    verify(inst, times(2)).retransformClasses(any());
    assertEquals(0, configurationUpdater.getAppliedDefinitions().size());
  }

  @Test
  public void acceptClearLogs() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    List<LogProbe> logProbes =
        singletonList(
            LogProbe.builder().probeId(LOG_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    configurationUpdater.accept(REMOTE_CONFIG, null);
    verify(inst).removeTransformer(any());
    verify(inst, times(2)).retransformClasses(any());
    assertEquals(0, configurationUpdater.getAppliedDefinitions().size());
  }

  @Test
  public void acceptChangeProbeToMetric() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses())
        .thenReturn(new Class[] {String.class, HashMap.class, StringBuilder.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    List<LogProbe> logProbes =
        Arrays.asList(
            LogProbe.builder()
                .language(LANGUAGE)
                .probeId(PROBE_ID)
                .where("java.lang.String", "concat")
                .build(),
            LogProbe.builder()
                .language(LANGUAGE)
                .probeId(PROBE_ID2)
                .where("java.util.HashMap", "<init>", "void ()")
                .build());
    configurationUpdater.accept(REMOTE_CONFIG, logProbes);
    List<MetricProbe> metricProbes =
        singletonList(
            MetricProbe.builder()
                .language(LANGUAGE)
                .probeId(METRIC_ID)
                .where("java.lang.StringBuilder", "append")
                .build());
    configurationUpdater.accept(REMOTE_CONFIG, metricProbes);
    verify(inst).removeTransformer(any());
    ArgumentCaptor<Class<?>[]> captor = ArgumentCaptor.forClass(Class[].class);
    verify(inst, times(5)).retransformClasses(captor.capture());
    List<Class<?>[]> allValues = captor.getAllValues();
    assertEquals(String.class, allValues.get(0));
    assertEquals(HashMap.class, allValues.get(1));
    assertEquals(String.class, allValues.get(2));
    assertEquals(HashMap.class, allValues.get(3));
    assertEquals(StringBuilder.class, allValues.get(4));
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(METRIC_ID.getEncodedId()));
  }

  @Test
  public void acceptNewSpan() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    SpanProbe spanProbe =
        SpanProbe.builder().probeId(SPAN_ID).where("java.lang.String", "concat").build();
    configurationUpdater.accept(REMOTE_CONFIG, singletonList(spanProbe));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(SPAN_ID.getEncodedId()));
  }

  @Test
  public void acceptNewDecorationSpan() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSink);
    SpanDecorationProbe spanProbe =
        SpanDecorationProbe.builder()
            .probeId(SPAN_DECORATION_ID)
            .where("java.lang.String", "concat")
            .build();
    configurationUpdater.accept(REMOTE_CONFIG, singletonList(spanProbe));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(SPAN_DECORATION_ID.getEncodedId()));
  }

  @Test
  public void handleException() {
    ConfigurationUpdater configurationUpdater = createConfigUpdater(debuggerSinkWithMockStatusSink);
    Exception ex = new Exception("oops");
    configurationUpdater.handleException(LOG_PROBE_PREFIX + PROBE_ID.getId(), ex);
    verify(probeStatusSink).addError(eq(ProbeId.from(PROBE_ID.getId() + ":0")), eq(ex));
  }

  private DebuggerTransformer createTransformer(
      Config tracerConfig,
      Configuration configuration,
      DebuggerTransformer.InstrumentationListener listener,
      ProbeMetadata probeMetadata,
      DebuggerSink debuggerSink) {
    return transformer;
  }

  private ConfigurationUpdater createConfigUpdater(DebuggerSink sink) {
    return new ConfigurationUpdater(
        inst, this::createTransformer, tracerConfig, sink, new ClassesToRetransformFinder());
  }
}
