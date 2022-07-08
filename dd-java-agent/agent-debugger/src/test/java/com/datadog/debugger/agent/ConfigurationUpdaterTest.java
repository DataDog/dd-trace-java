package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ConfigurationUpdaterTest {
  private static final String LANGUAGE = "java";
  private static final String PROBE_ID = "beae1807-f3b0-4ea8-a74f-826790c5e6f8";
  private static final String PROBE_ID2 = "beae1808-f3b0-4ea8-a74f-826790c5e6f8";
  private static final String METRIC_ID = "cfbf2918-e4c1-5fb9-b85e-937881d6f7e9";
  private static final String METRIC_ID2 = "cfbf2919-e4c1-5fb9-b85e-937881d6f7e9";
  private static final String SERVICE_NAME = "service-name";
  private static final long ORG_ID = 2;

  @Mock private Instrumentation inst;
  @Mock private DebuggerTransformer transformer;
  @Mock private Config tracerConfig;
  @Mock private DebuggerSink debuggerSink;
  @Mock private ProbeStatusSink probeStatusSink;

  private DebuggerSink debuggerSinkWithMockStatusSink;

  @BeforeEach
  void setUp() {
    lenient().when(tracerConfig.getFinalDebuggerSnapshotUrl()).thenReturn("http://localhost");
    debuggerSinkWithMockStatusSink = new DebuggerSink(tracerConfig, probeStatusSink);
  }

  @Test
  public void acceptNoProbes() {
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(inst, this::createTransformer, tracerConfig, debuggerSink);
    assertFalse(configurationUpdater.accept(null));
    verify(inst, never()).addTransformer(any(), eq(true));
    verifyNoInteractions(debuggerSink);
  }

  @Test
  public void acceptNewProbe() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, debuggerSinkWithMockStatusSink);
    List<SnapshotProbe> snapshotProbes =
        Collections.singletonList(
            SnapshotProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID));
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
            inst, this::createTransformer, tracerConfig, debuggerSinkWithMockStatusSink);
    List<SnapshotProbe> snapshotProbes =
        Arrays.stream(classes)
            .map(getClassName)
            .map(
                className ->
                    SnapshotProbe.builder().probeId(PROBE_ID).where(className, "foo").build())
            .collect(Collectors.toList());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
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
            inst, this::createTransformer, tracerConfig, debuggerSinkWithMockStatusSink);
    List<SnapshotProbe> snapshotProbes =
        Arrays.asList(
            SnapshotProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build(),
            SnapshotProbe.builder().probeId(PROBE_ID2).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID));
    assertEquals(1, appliedDefinitions.get(PROBE_ID).getAdditionalProbes().size());
    assertEquals(PROBE_ID2, appliedDefinitions.get(PROBE_ID).getAdditionalProbes().get(0).getId());
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
  }

  @Test
  public void accept2PhaseDuplicatedProbes() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, debuggerSinkWithMockStatusSink);
    // phase 1: single probe definition
    List<SnapshotProbe> snapshotProbes =
        Arrays.asList(
            SnapshotProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID));
    // phase 2: add duplicated probe definitions
    snapshotProbes =
        Arrays.asList(
            SnapshotProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build(),
            SnapshotProbe.builder().probeId(PROBE_ID2).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertEquals(1, appliedDefinitions.get(PROBE_ID).getAdditionalProbes().size());
    assertEquals(PROBE_ID2, appliedDefinitions.get(PROBE_ID).getAdditionalProbes().get(0).getId());
    verify(probeStatusSink, times(2)).addReceived(eq(PROBE_ID)); // re-installed for PROBE_ID2
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
  }

  @Test
  public void acceptRemoveDuplicatedProbes() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, debuggerSinkWithMockStatusSink);
    // phase 1: duplicated probe definition
    List<SnapshotProbe> snapshotProbes =
        Arrays.asList(
            SnapshotProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build(),
            SnapshotProbe.builder().probeId(PROBE_ID2).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertEquals(1, appliedDefinitions.get(PROBE_ID).getAdditionalProbes().size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID));
    assertEquals(PROBE_ID2, appliedDefinitions.get(PROBE_ID).getAdditionalProbes().get(0).getId());
    // phase 2: remove duplicated probe definitions
    snapshotProbes =
        Arrays.asList(
            SnapshotProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID));
    assertEquals(0, appliedDefinitions.get(PROBE_ID).getAdditionalProbes().size());
    verify(probeStatusSink, times(2)).addReceived(eq(PROBE_ID)); // re-installed for PROBE_ID2
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
  }

  @Test
  public void acceptDontDedupMetricProbes() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, debuggerSinkWithMockStatusSink);
    List<SnapshotProbe> snapshotProbes =
        Arrays.asList(
            SnapshotProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    List<MetricProbe> metricProbes =
        Arrays.asList(
            MetricProbe.builder().metricId(METRIC_ID).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes, metricProbes)));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(2, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID));
    assertTrue(appliedDefinitions.containsKey(METRIC_ID));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(METRIC_ID));
  }

  @Test
  public void acceptSourceFileLineNumber() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(inst, this::createTransformer, tracerConfig);
    List<SnapshotProbe> snapshotProbes =
        Collections.singletonList(
            SnapshotProbe.builder()
                .probeId(PROBE_ID)
                .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
                .build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    verify(inst).retransformClasses(eq(String.class));
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID));
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
        new ConfigurationUpdater(inst, this::createTransformer, tracerConfig);
    List<SnapshotProbe> snapshotProbes =
        Collections.singletonList(
            SnapshotProbe.builder()
                .probeId(PROBE_ID)
                .where("", "", "", 1966, "java/lang/String.java")
                .build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    verify(inst).retransformClasses(eq(String.class));
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID));
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
        new ConfigurationUpdater(inst, this::createTransformer, tracerConfig);
    List<SnapshotProbe> snapshotProbes =
        Collections.singletonList(
            SnapshotProbe.builder()
                .probeId(PROBE_ID)
                .where("", "", "", 136, "ConfigurationUpdaterTest$2")
                .build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    verify(inst).retransformClasses(eq(runnable.getClass()));
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(PROBE_ID));
  }

  @Test
  public void acceptDeactivatedProbes() {
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            (tracerConfig, configuration, listener) -> {
              assertEquals(0, configuration.getDefinitions().size());
              return transformer;
            },
            tracerConfig);
    List<SnapshotProbe> snapshotProbes =
        Arrays.asList(
            SnapshotProbe.builder()
                .language(LANGUAGE)
                .probeId(PROBE_ID)
                .active(false)
                .where("java.lang.String", "concat")
                .build(),
            SnapshotProbe.builder()
                .language(LANGUAGE)
                .probeId(PROBE_ID2)
                .active(false)
                .where("java.util.HashMap", "<init>", "void ()")
                .build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(inst, never()).addTransformer(any(), eq(true));
    verify(inst, never()).getAllLoadedClasses();
    verify(inst, never()).removeTransformer(any());
    assertEquals(0, configurationUpdater.getAppliedDefinitions().size());
  }

  @Test
  public void acceptDeactivateProbe() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    AtomicInteger expectedDefinitions = new AtomicInteger(1);
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            (tracerConfig, configuration, listener) -> {
              assertEquals(expectedDefinitions.get(), configuration.getDefinitions().size());
              return transformer;
            },
            tracerConfig);
    List<SnapshotProbe> snapshotProbes =
        Collections.singletonList(
            SnapshotProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    snapshotProbes =
        Collections.singletonList(
            SnapshotProbe.builder()
                .probeId(PROBE_ID)
                .active(false)
                .where("java.lang.String", "concat")
                .build());
    expectedDefinitions.set(0);
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(inst, times(1)).addTransformer(any(), eq(true)); // no transformer when no more probe
    verify(inst, times(2)).getAllLoadedClasses();
    verify(inst, times(2)).retransformClasses(any());
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(0, appliedDefinitions.size());
  }

  @Test
  public void acceptDeleteProbe() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class, HashMap.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, debuggerSinkWithMockStatusSink);
    SnapshotProbe probe1 =
        SnapshotProbe.builder()
            .language(LANGUAGE)
            .probeId(PROBE_ID)
            .where("java.lang.String", "concat")
            .build();
    SnapshotProbe probe2 =
        SnapshotProbe.builder()
            .language(LANGUAGE)
            .probeId(PROBE_ID2)
            .where("java.util.HashMap", "<init>", "void ()")
            .build();
    List<SnapshotProbe> snapshotProbes = Arrays.asList(probe1, probe2);
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
    snapshotProbes = Collections.singletonList(probe1);
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
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
    assertTrue(appliedDefinitions.containsKey(probe1.id));
  }

  @Test
  public void acceptDeleteProbeSameClass() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    AtomicInteger expectedDefinitions = new AtomicInteger(2);
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst,
            (tracerConfig, configuration, listener) -> {
              assertEquals(expectedDefinitions.get(), configuration.getDefinitions().size());
              return transformer;
            },
            tracerConfig,
            debuggerSinkWithMockStatusSink);
    SnapshotProbe probe1 =
        SnapshotProbe.builder()
            .language(LANGUAGE)
            .probeId(PROBE_ID)
            .where("java.lang.String", "concat")
            .build();
    SnapshotProbe probe2 =
        SnapshotProbe.builder()
            .language(LANGUAGE)
            .probeId(PROBE_ID2)
            .where("java.lang.String", "indexOf", "int (int)")
            .build();
    List<SnapshotProbe> snapshotProbes = Arrays.asList(probe1, probe2);
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    verify(probeStatusSink).addReceived(eq(PROBE_ID2));
    snapshotProbes = Collections.singletonList(probe1);
    expectedDefinitions.set(1);
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(probeStatusSink).removeDiagnostics(eq(PROBE_ID2));
    verify(inst).removeTransformer(any());
    ArgumentCaptor<Class<?>[]> captor = ArgumentCaptor.forClass(Class[].class);
    verify(inst, times(2)).retransformClasses(captor.capture());
    List<Class<?>[]> allValues = captor.getAllValues();
    assertEquals(String.class, allValues.get(0));
    assertEquals(String.class, allValues.get(1));
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    Assertions.assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(probe1.id));
  }

  @Test
  public void acceptClearProbes() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            inst, this::createTransformer, tracerConfig, debuggerSinkWithMockStatusSink);
    List<SnapshotProbe> snapshotProbes =
        Collections.singletonList(
            SnapshotProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(probeStatusSink).addReceived(eq(PROBE_ID));
    assertFalse(configurationUpdater.accept(null));
    verify(probeStatusSink).removeDiagnostics(eq(PROBE_ID));
    verify(inst).removeTransformer(any());
    verify(inst, times(2)).retransformClasses(any());
    Assertions.assertEquals(0, configurationUpdater.getAppliedDefinitions().size());
  }

  @Test
  public void resolve() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(inst, this::createTransformer, tracerConfig);
    List<SnapshotProbe> snapshotProbes =
        Collections.singletonList(
            SnapshotProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    Snapshot.ProbeDetails probeDetails = configurationUpdater.resolve(PROBE_ID, String.class);
    Assertions.assertEquals(PROBE_ID, probeDetails.getId());
    Assertions.assertEquals("java.lang.String", probeDetails.getLocation().getType());
    Assertions.assertEquals("concat", probeDetails.getLocation().getMethod());
  }

  @Test
  public void resolveFails() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(inst, this::createTransformer, tracerConfig);
    List<SnapshotProbe> snapshotProbes =
        Collections.singletonList(
            SnapshotProbe.builder().probeId(PROBE_ID).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    verify(inst).retransformClasses(eq(String.class));
    // simulate that there is a snapshot probe instrumentation left in HashMap class
    Snapshot.ProbeDetails probeDetails = configurationUpdater.resolve(PROBE_ID2, HashMap.class);
    Assertions.assertNull(probeDetails);
    verify(inst).retransformClasses(eq(HashMap.class));
  }

  @Test
  public void acceptMaxProbes() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    List<SnapshotProbe> snapshotProbes = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      snapshotProbes.add(
          SnapshotProbe.builder()
              .probeId(String.valueOf(i))
              .where("java.lang.String", "concat" + i)
              .build());
    }
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(inst, this::createTransformer, tracerConfig);
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    Assertions.assertEquals(
        ConfigurationUpdater.MAX_ALLOWED_PROBES,
        configurationUpdater.getAppliedDefinitions().size());
  }

  private static Stream<Arguments> provideEnvAndVersion() {
    return Stream.of(
        // <Agent env>, <agent version>, <probe tags>, <should match>
        Arguments.of("dev", "foo", new String[] {"env:dev", "version:foo"}, true),
        Arguments.of("prod", "bar", new String[] {"env:dev", "version:foo"}, false),
        Arguments.of("prod", "--wildcard--", new String[] {"env:prod", "version"}, true),
        Arguments.of("--wildcard--", "foo", new String[] {"env", "version:foo"}, true),
        Arguments.of("--wildcard--", "--wildcard--", new String[] {"env", "version"}, true),
        Arguments.of("", "", new String[] {"env:dev", "version:foo"}, false),
        Arguments.of("", "", new String[] {"env", "version"}, true),
        Arguments.of("", "", new String[] {}, true),
        Arguments.of("foo", "bar", new String[] {}, true),
        Arguments.of("foo", "", new String[] {}, true),
        Arguments.of("", "bar", new String[] {}, true),
        Arguments.of("", "bar", null, true));
  }

  @ParameterizedTest
  @MethodSource("provideEnvAndVersion")
  public void acceptProbesBasedOnEnvAndVersion(
      String agentEnv, String agentVersion, String[] tags, boolean expectation) {
    lenient().when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    lenient().when(tracerConfig.getEnv()).thenReturn(agentEnv);
    lenient().when(tracerConfig.getVersion()).thenReturn(agentVersion);
    List<SnapshotProbe> snapshotProbes = new ArrayList<>();
    snapshotProbes.add(
        SnapshotProbe.builder()
            .probeId("foo")
            .tags(tags)
            .where("java.lang.String", "concat")
            .build());
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(inst, this::createTransformer, tracerConfig);
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    Assertions.assertEquals(
        expectation ? 1 : 0, configurationUpdater.getAppliedDefinitions().size());
  }

  @Test
  public void acceptNewMetric() {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(inst, this::createTransformer, tracerConfig);
    List<MetricProbe> metricProbes =
        Collections.singletonList(
            MetricProbe.builder().metricId(METRIC_ID).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createAppMetrics(metricProbes)));
    verify(inst).addTransformer(any(), eq(true));
    verify(inst).getAllLoadedClasses();
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(METRIC_ID));
  }

  @Test
  public void acceptDeleteMetric() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class, HashMap.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(inst, this::createTransformer, tracerConfig);
    MetricProbe metricProbe1 =
        MetricProbe.builder()
            .language(LANGUAGE)
            .metricId(METRIC_ID)
            .where("java.lang.String", "concat")
            .build();
    MetricProbe metricProbe2 =
        MetricProbe.builder()
            .language(LANGUAGE)
            .metricId(METRIC_ID2)
            .where("java.util.HashMap", "<init>", "void ()")
            .build();
    List<MetricProbe> metricProbes = Arrays.asList(metricProbe1, metricProbe2);
    assertTrue(configurationUpdater.accept(createAppMetrics(metricProbes)));
    metricProbes = Collections.singletonList(metricProbe1);
    assertTrue(configurationUpdater.accept(createAppMetrics(metricProbes)));
    verify(inst).removeTransformer(any());
    ArgumentCaptor<Class<?>[]> captor = ArgumentCaptor.forClass(Class[].class);
    verify(inst, times(3)).retransformClasses(captor.capture());
    List<Class<?>[]> allValues = captor.getAllValues();
    assertEquals(String.class, allValues.get(0));
    assertEquals(HashMap.class, allValues.get(1));
    assertEquals(HashMap.class, allValues.get(2)); // for removing instrumentation
    Map<String, ProbeDefinition> appliedDefinitions = configurationUpdater.getAppliedDefinitions();
    assertEquals(1, appliedDefinitions.size());
    assertTrue(appliedDefinitions.containsKey(metricProbe1.id));
  }

  @Test
  public void acceptClearMetrics() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {String.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(inst, this::createTransformer, tracerConfig);
    List<MetricProbe> metricProbes =
        Collections.singletonList(
            MetricProbe.builder().metricId(METRIC_ID).where("java.lang.String", "concat").build());
    assertTrue(configurationUpdater.accept(createAppMetrics(metricProbes)));
    assertFalse(configurationUpdater.accept(null));
    verify(inst).removeTransformer(any());
    verify(inst, times(2)).retransformClasses(any());
    assertEquals(0, configurationUpdater.getAppliedDefinitions().size());
  }

  @Test
  public void acceptChangeProbeToMetric() throws UnmodifiableClassException {
    when(inst.getAllLoadedClasses())
        .thenReturn(new Class[] {String.class, HashMap.class, StringBuilder.class});
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(inst, this::createTransformer, tracerConfig);
    List<SnapshotProbe> snapshotProbes =
        Arrays.asList(
            SnapshotProbe.builder()
                .language(LANGUAGE)
                .probeId(PROBE_ID)
                .where("java.lang.String", "concat")
                .build(),
            SnapshotProbe.builder()
                .language(LANGUAGE)
                .probeId(PROBE_ID2)
                .where("java.util.HashMap", "<init>", "void ()")
                .build());
    assertTrue(configurationUpdater.accept(createApp(snapshotProbes)));
    List<MetricProbe> metricProbes =
        Collections.singletonList(
            MetricProbe.builder()
                .language(LANGUAGE)
                .metricId(METRIC_ID)
                .where("java.lang.StringBuilder", "append")
                .build());
    assertTrue(configurationUpdater.accept(createAppMetrics(metricProbes)));
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
    assertTrue(appliedDefinitions.containsKey(METRIC_ID));
  }

  private static Configuration createApp(List<SnapshotProbe> snapshotProbes) {
    return new Configuration(SERVICE_NAME, ORG_ID, snapshotProbes);
  }

  private static Configuration createApp(
      List<SnapshotProbe> snapshotProbes, List<MetricProbe> metricProbes) {
    return new Configuration(SERVICE_NAME, ORG_ID, snapshotProbes, metricProbes);
  }

  private static Configuration createAppMetrics(List<MetricProbe> metricProbes) {
    return new Configuration(SERVICE_NAME, ORG_ID, null, metricProbes);
  }

  private DebuggerTransformer createTransformer(
      Config tracerConfig,
      Configuration configuration,
      DebuggerTransformer.InstrumentationListener listener) {
    return transformer;
  }
}
