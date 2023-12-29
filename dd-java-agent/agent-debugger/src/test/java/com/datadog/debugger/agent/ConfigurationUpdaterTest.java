package com.datadog.debugger.agent;

import static java.util.Collections.emptyList;
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
  private static final String SERVICE_NAME = "service-name";

  @Mock private Instrumentation inst;
  @Mock private DebuggerTransformer transformer;
  @Mock private Config tracerConfig;
  @Mock private DebuggerSink debuggerSink;
  @Mock private ProbeStatusSink probeStatusSink;

  private DebuggerSink debuggerSinkWithMockStatusSink;

  @BeforeEach
  void setUp() {
    lenient().when(tracerConfig.getFinalDebuggerSnapshotUrl()).thenReturn("http://localhost");
    lenient().when(tracerConfig.getDebuggerUploadBatchSize()).thenReturn(100);
    lenient().when(tracerConfig.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost");

    debuggerSinkWithMockStatusSink = new DebuggerSink(tracerConfig, probeStatusSink);
  }

  @Test
  public void acceptNoProbes() {
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            this::createTransformer,
            tracerConfig,
            debuggerSink,
            new ClassesToRetransformFinder());
    configurationUpdater.accept(null);
    verify(inst, never()).addTransformer(any(), eq(true));
    verifyNoInteractions(debuggerSink);
  }

  @Test
  public void acceptNewProbe() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            this::createTransformer,
            tracerConfig,
            debuggerSinkWithMockStatusSink,
            new ClassesToRetransformFinder());
    List<LogProbe> logProbes =
        Collections.singletonList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(createApp(logProbes));
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
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            this::createTransformer,
            tracerConfig,
            debuggerSinkWithMockStatusSink,
            new ClassesToRetransformFinder());
    List<LogProbe> logProbes =
        Arrays.stream(classes)
            .map(getClassName)
            .map(className -> LogProbe.builder().probeId(PROBE_ID).where(className, "foo").build())
            .collect(Collectors.toList());
    configurationUpdater.accept(createApp(logProbes));
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
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            this::createTransformer,
            tracerConfig,
            debuggerSinkWithMockStatusSink,
            new ClassesToRetransformFinder());
    List<LogProbe> logProbes =
        Arrays.asList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build(),
            LogProbe.builder().probeId(PROBE_ID2).where("java.lang.String", "concat").build());
    configurationUpdater.accept(createApp(logProbes));
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
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            this::createTransformer,
            tracerConfig,
            debuggerSinkWithMockStatusSink,
            new ClassesToRetransformFinder());
    // phase 1: single probe definition
    List<LogProbe> logProbes =
        Arrays.asList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(createApp(logProbes));
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
    configurationUpdater.accept(createApp(logProbes));
    appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(2, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID2.getEncodedId()));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
  }

  @Test
  public void acceptRemoveDuplicatedProbes() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            this::createTransformer,
            tracerConfig,
            debuggerSinkWithMockStatusSink,
            new ClassesToRetransformFinder());
    // phase 1: duplicated probe definition
    List<LogProbe> logProbes =
        Arrays.asList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build(),
            LogProbe.builder().probeId(PROBE_ID2).where("java.lang.String", "concat").build());
    configurationUpdater.accept(createApp(logProbes));
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
    configurationUpdater.accept(createApp(logProbes));
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
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            this::createTransformer,
            tracerConfig,
            debuggerSinkWithMockStatusSink,
            new ClassesToRetransformFinder());
    List<LogProbe> logProbes =
        Arrays.asList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    List<MetricProbe> metricProbes =
        Arrays.asList(
            MetricProbe.builder().probeId(METRIC_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(createApp(metricProbes, logProbes, emptyList()));
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
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
    List<LogProbe> logProbes =
        Collections.singletonList(
            LogProbe.builder()
                .probeId(PROBE_ID)
                .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
                .build());
    configurationUpdater.accept(createApp(logProbes));
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
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
    List<LogProbe> logProbes =
        Collections.singletonList(
            LogProbe.builder()
                .probeId(PROBE_ID)
                .where("", "", "", 1966, "java/lang/String.java")
                .build());
    configurationUpdater.accept(createApp(logProbes));
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
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
    List<LogProbe> logProbes =
        Collections.singletonList(
            LogProbe.builder()
                .probeId(PROBE_ID)
                .where("", "", "", 136, "ConfigurationUpdaterTest$2")
                .build());
    configurationUpdater.accept(createApp(logProbes));
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
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            this::createTransformer,
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
            .where("java.util.HashMap", "<init>", "void ()")
            .build();
    List<LogProbe> logProbes = Arrays.asList(probe1, probe2);
    configurationUpdater.accept(createApp(logProbes));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
    logProbes = Collections.singletonList(probe1);
    configurationUpdater.accept(createApp(logProbes));
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
            (tracerConfig, configuration, listener, debuggerSink) -> {
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
    configurationUpdater.accept(createApp(logProbes));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
    logProbes = Collections.singletonList(probe1);
    expectedDefinitions.set(1);
    configurationUpdater.accept(createApp(logProbes));
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
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            this::createTransformer,
            tracerConfig,
            debuggerSinkWithMockStatusSink,
            new ClassesToRetransformFinder());
    List<LogProbe> logProbes =
        Collections.singletonList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(createApp(logProbes));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    configurationUpdater.accept(null);
    verify(probeStatusSink).removeDiagnostics(eq(PROBE_ID));
    verify(inst).removeTransformer(any());
    verify(inst, times(2)).retransformClasses(any());
    Assertions.assertEquals(0, configurationUpdater.getAppliedDefinitions().size());
  }

  @Test
  public void resolve() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
    List<LogProbe> logProbes =
        Collections.singletonList(
            LogProbe.builder()
                .probeId(PROBE_ID)
                .where("java.lang.String", "concat")
                .when(
                    new ProbeCondition(
                        DSL.when(DSL.eq(DSL.ref("arg"), DSL.value("foo"))), "arg == 'foo'"))
                .build());
    logProbes.get(0).buildLocation(null);
    configurationUpdater.accept(createApp(logProbes));
    ProbeImplementation probeImplementation = configurationUpdater.resolve(PROBE_ID.getEncodedId());
    Assertions.assertEquals(
        PROBE_ID.getEncodedId(), probeImplementation.getProbeId().getEncodedId());
    Assertions.assertEquals("java.lang.String", probeImplementation.getLocation().getType());
    Assertions.assertEquals("concat", probeImplementation.getLocation().getMethod());
    Assertions.assertNotNull(((LogProbe) probeImplementation).getProbeCondition());
  }

  @Test
  public void resolveFails() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
    List<LogProbe> logProbes =
        Collections.singletonList(
            LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(createApp(logProbes));
    verify(inst).retransformClasses(eq(String.class));
    // simulate that there is a snapshot probe instrumentation left in HashMap class
    ProbeImplementation probeImplementation =
        configurationUpdater.resolve(PROBE_ID2.getEncodedId());
    Assertions.assertNull(probeImplementation);
  }

  @Test
  public void acceptMaxProbes() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    List<LogProbe> logProbes = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      logProbes.add(
          LogProbe.builder()
              .probeId(String.valueOf(i), 0)
              .where("java.lang.String", "concat" + i)
              .build());
    }
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
    configurationUpdater.accept(createApp(logProbes));
    Assertions.assertEquals(
        ConfigurationUpdater.MAX_ALLOWED_LOG_PROBES,
        configurationUpdater.getAppliedDefinitions().size());
  }

  @Test
  public void acceptNewMetric() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
    List<MetricProbe> metricProbes =
        Collections.singletonList(
            MetricProbe.builder().probeId(METRIC_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(createAppMetrics(metricProbes));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(METRIC_ID.getEncodedId()));
  }

  @Test
  public void acceptNewLog() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
    List<LogProbe> logProbes =
        Collections.singletonList(
            LogProbe.builder().probeId(LOG_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(createAppLogs(logProbes));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(LOG_ID.getEncodedId()));
  }

  @Test
  public void acceptDeleteMetric() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class, HashMap.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
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
    configurationUpdater.accept(createAppMetrics(metricProbes));
    metricProbes = Collections.singletonList(metricProbe1);
    configurationUpdater.accept(createAppMetrics(metricProbes));
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
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
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
    configurationUpdater.accept(createAppLogs(logProbes));
    logProbes = Collections.singletonList(logProbe1);
    configurationUpdater.accept(createAppLogs(logProbes));
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
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
    List<MetricProbe> metricProbes =
        Collections.singletonList(
            MetricProbe.builder().probeId(METRIC_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(createAppMetrics(metricProbes));
    configurationUpdater.accept(null);
    verify(inst).removeTransformer(any());
    verify(inst, times(2)).retransformClasses(any());
    assertEquals(0, configurationUpdater.getAppliedDefinitions().size());
  }

  @Test
  public void acceptClearLogs() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
    List<LogProbe> logProbes =
        Collections.singletonList(
            LogProbe.builder().probeId(LOG_ID).where("java.lang.String", "concat").build());
    configurationUpdater.accept(createAppLogs(logProbes));
    configurationUpdater.accept(null);
    verify(inst).removeTransformer(any());
    verify(inst, times(2)).retransformClasses(any());
    assertEquals(0, configurationUpdater.getAppliedDefinitions().size());
  }

  @Test
  public void acceptChangeProbeToMetric() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses())
        .thenReturn(new Class[] {String.class, HashMap.class, StringBuilder.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, new ClassesToRetransformFinder());
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
    configurationUpdater.accept(createApp(logProbes));
    List<MetricProbe> metricProbes =
        Collections.singletonList(
            MetricProbe.builder()
                .language(LANGUAGE)
                .probeId(METRIC_ID)
                .where("java.lang.StringBuilder", "append")
                .build());
    configurationUpdater.accept(createAppMetrics(metricProbes));
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

  private static Configuration createApp(List<LogProbe> logProbes) {
    return Configuration.builder().setService(SERVICE_NAME).addLogProbes(logProbes).build();
  }

  private static Configuration createApp(
      List<MetricProbe> metricProbes, List<LogProbe> logProbes, List<SpanProbe> spanProbes) {
    return new Configuration(SERVICE_NAME, metricProbes, logProbes, spanProbes);
  }

  private static Configuration createAppMetrics(List<MetricProbe> metricProbes) {
    return Configuration.builder().setService(SERVICE_NAME).addMetricProbes(metricProbes).build();
  }

  private static Configuration createAppLogs(List<LogProbe> logProbes) {
    return Configuration.builder().setService(SERVICE_NAME).addLogProbes(logProbes).build();
  }

  private DebuggerTransformer createTransformer(
      Config tracerConfig,
      Configuration configuration,
      DebuggerTransformer.InstrumentationListener listener,
      DebuggerSink debuggerSink) {
    return transformer;
  }
}
