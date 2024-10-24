package com.datadog.debugger.origin;

import static com.datadog.debugger.util.TestHelper.setFieldInConfig;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_FRAME;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_PREFIX;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_TYPE;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.agent.CapturingTestBase;
import com.datadog.debugger.codeorigin.CodeOriginProbeManager;
import com.datadog.debugger.codeorigin.DefaultCodeOriginRecorder;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.ClassNameFiltering;
import com.datadog.debugger.util.TestSnapshotListener;
import com.datadog.debugger.util.TestTraceInterceptor;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.core.CoreTracer;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.joor.Reflect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CodeOriginTest extends CapturingTestBase {

  private CodeOriginProbeManager probeManager;

  private TestTraceInterceptor traceInterceptor;

  public static ClassNameFilter classNameFilter =
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

  @Override
  @BeforeEach
  public void before() {
    super.before();
    traceInterceptor = new TestTraceInterceptor();

    CoreTracer tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    tracer.addTraceInterceptor(traceInterceptor);

    setFieldInConfig(Config.get(), "debuggerCodeOriginEnabled", true);
    setFieldInConfig(InstrumenterConfig.get(), "codeOriginEnabled", true);
  }

  @Test
  public void basicInstrumentation() throws Exception {
    final String className = "com.datadog.debugger.CodeOrigin01";

    installProbes(codeOriginProbes(className).toArray(new LogProbe[0]));
    final Class<?> testClass = compileAndLoadClass(className);
    checkResults(testClass, "fullTrace", false, 0);
  }

  @Test
  public void withDebug1() throws Exception {
    final String className = "com.datadog.debugger.CodeOrigin02";
    installProbes(codeOriginProbes(className).toArray(new LogProbe[0]));
    final Class<?> testClass = compileAndLoadClass(className);
    checkResults(testClass, "debug_1", true, 0);
  }

  @Test
  public void withLogProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CodeOrigin03";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "entry", "()")
            .capture(1, 100, 255, Limits.DEFAULT_FIELD_COUNT)
            .build();
    List<LogProbe> probes = codeOriginProbes(CLASS_NAME);
    probes.add(logProbe);
    installProbes(probes.toArray(new LogProbe[0]));
    final Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    checkResults(testClass, "debug_1", true, 1);
  }

  @NotNull
  private List<LogProbe> codeOriginProbes(String type) {
    CodeOriginProbe entry =
        new CodeOriginProbe(
            new ProbeId(UUID.randomUUID().toString(), 0),
            "()",
            Where.of(type, "entry", "()", "53"),
            probeManager);
    CodeOriginProbe exit =
        new CodeOriginProbe(
            new ProbeId(UUID.randomUUID().toString(), 0),
            null,
            Where.of(type, "exit", "()", "60"),
            probeManager);
    return new ArrayList<>(asList(entry, exit));
  }

  private void checkResults(
      Class<?> testClass, String parameter, boolean includeSnapshot, int expectedProbeCount) {
    Reflect.onClass(testClass).call("main", parameter).get();
    int result = Reflect.onClass(testClass).call("main", parameter).get();
    assertEquals(0, result);
    List<? extends MutableSpan> spans = traceInterceptor.getTrace();
    assertEquals(3, spans.size());
    assertEquals("main", spans.get(2).getLocalRootSpan().getOperationName());

    List<? extends MutableSpan> list =
        spans.stream()
            .filter(span -> !span.getOperationName().equals("exit"))
            .collect(Collectors.toList());

    for (MutableSpan span : list) {
      checkEntrySpanTags(span, includeSnapshot);
    }
    Optional<? extends MutableSpan> exit =
        spans.stream().filter(span -> span.getOperationName().equals("exit")).findFirst();
    assertTrue(exit.isPresent());
    exit.ifPresent(span -> checkExitSpanTags(span, includeSnapshot));
  }

  @Override
  protected TestSnapshotListener installProbes(LogProbe... probes) {
    TestSnapshotListener listener = super.installProbes(probes);

    DebuggerContext.initClassNameFilter(classNameFilter);

    probeManager = new CodeOriginProbeManager(configurationUpdater);
    DebuggerContext.initCodeOrigin(new DefaultCodeOriginRecorder(probeManager));

    return listener;
  }

  private static void checkEntrySpanTags(MutableSpan span, boolean includeSnapshot) {
    String keys = format("Existing keys for %s: %s", span.getOperationName(), ldKeys(span));

    assertEquals(span.getTag(DD_CODE_ORIGIN_TYPE), "entry", keys);
    assertKeyPresent(span, DD_CODE_ORIGIN_TYPE);
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "file"));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "line"));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "method"));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "signature"));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "type"));

    if (includeSnapshot) {
      assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "snapshot_id"));
    } else {
      assertKeyNotPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "snapshot_id"));
    }
  }

  private static void assertKeyPresent(MutableSpan span, String key) {
    assertNotNull(
        span.getTag(key), format("'%s' key missing in '%s' span.", key, span.getOperationName()));
  }

  private static void assertKeyNotPresent(MutableSpan span, String key) {
    assertNull(
        span.getTag(key),
        format("'%s' key found in '%s' span when it shouldn't be.", key, span.getOperationName()));
  }

  private static void checkExitSpanTags(MutableSpan span, boolean includeSnapshot) {
    String keys =
        format("Existing keys for %s: %s", span.getOperationName(), new TreeSet<>(ldKeys(span)));

    assertKeyPresent(span, DD_CODE_ORIGIN_TYPE);
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "file"));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "line"));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "method"));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "type"));
    if (includeSnapshot) {
      assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "snapshot_id"));
    }

    MutableSpan rootSpan = span.getLocalRootSpan();
    assertEquals(rootSpan.getTag(DD_CODE_ORIGIN_TYPE), "entry", keys);
    assertNotNull(rootSpan.getTag(format(DD_CODE_ORIGIN_FRAME, 1, "file")));
    assertNotNull(rootSpan.getTag(format(DD_CODE_ORIGIN_FRAME, 1, "line")));
    assertNotNull(rootSpan.getTag(format(DD_CODE_ORIGIN_FRAME, 1, "method")));
    assertNotNull(rootSpan.getTag(format(DD_CODE_ORIGIN_FRAME, 1, "type")));
  }

  private static Set<String> ldKeys(MutableSpan span) {
    return span.getTags().keySet().stream()
        .filter(key -> key.startsWith(DD_CODE_ORIGIN_PREFIX))
        .collect(Collectors.toCollection(TreeSet::new));
  }
}
