package com.datadog.debugger.origin;

import static datadog.trace.bootstrap.debugger.CapturedContext.EMPTY_CAPTURING_CONTEXT;
import static datadog.trace.util.AgentThreadFactory.AgentThread.TASK_SCHEDULER;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.agent.DebuggerAgentHelper;
import com.datadog.debugger.codeorigin.DebuggerConfiguration;
import com.datadog.debugger.codeorigin.DefaultCodeOriginRecorder;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.LogProbe.LogStatus;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.util.ClassNameFiltering;
import com.datadog.debugger.util.TestSnapshotListener;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.spanorigin.CodeOriginInfo;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.core.CoreTracer;
import datadog.trace.test.util.DDSpecification;
import datadog.trace.util.AgentTaskScheduler;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CodeOriginTest extends DDSpecification {
  private ClassNameFiltering classNameFiltering;

  private ConfigurationUpdater configurationUpdater;

  private int count = 0;

  private TestSnapshotListener listener;

  private DefaultCodeOriginRecorder codeOriginRecorder;
  private TracerAPI tracerAPI;

  @BeforeEach
  public void setUp() {
    injectSysConfig("dd.code.origin.enabled", "true");
    AgentTracer.registerIfAbsent(CoreTracer.builder().build());
    tracerAPI = AgentTracer.get();
    configurationUpdater = mock(ConfigurationUpdater.class);
    classNameFiltering =
        new ClassNameFiltering(
            new HashSet<>(
                Arrays.asList(
                    "sun",
                    "org.junit",
                    "java.",
                    "org.gradle",
                    "com.sun",
                    "worker.org.gradle",
                    "datadog",
                    "com.datadog.debugger.probe",
                    "com.datadog.debugger.codeorigin")));
    codeOriginRecorder = new DefaultCodeOriginRecorder(configurationUpdater, classNameFiltering);
    codeOriginRecorder.taskScheduler(
        new AgentTaskScheduler(TASK_SCHEDULER) {
          @Override
          public void execute(Runnable target) {
            target.run();
          }
        });
    DebuggerContext.initCodeOrigin(codeOriginRecorder);

    listener = new TestSnapshotListener(createConfig(), mock(ProbeStatusSink.class));
    DebuggerAgentHelper.injectSink(listener);
  }

  @Test
  public void fingerprinting() {

    AgentSpan span1 = createProbePath1();
    AgentSpan span2 = createProbePath1a();
    AgentSpan span3 = createProbePath1();

    CodeOriginProbe[] probes =
        codeOriginRecorder.probeManager().getProbes().toArray(new CodeOriginProbe[0]);

    assertEquals(2, probes.length);
    Arrays.sort(probes, (o1, o2) -> o1.isEntrySpanProbe() ? -1 : 1);
    CodeOriginProbe entryProbe = probes[0];
    CodeOriginProbe exitProbe = probes[1];

    invoke(span1, entryProbe);
    DebuggerConfiguration.enableDebug(span2);
    invoke(span2, exitProbe);
    invoke(span3, entryProbe);

    assertTrue(
        span1.getTags().containsKey(DDTags.DD_CODE_ORIGIN_LOCATION_FILE),
        span1.getTags().keySet().toString());
    assertFalse(
        span1.getTags().containsKey(DDTags.DD_CODE_ORIGIN_LOCATION_SNAPSHOT_ID),
        span1.getTags().keySet().toString());

    assertTrue(
        span2.getTags().containsKey(String.format(DDTags.DD_EXIT_LOCATION_FILE, 0)),
        span2.getTags().keySet().toString());
    assertTrue(
        span2.getTags().containsKey(DDTags.DD_EXIT_LOCATION_SNAPSHOT_ID),
        span2.getTags().keySet().toString());

    assertTrue(
        span3.getTags().containsKey(DDTags.DD_CODE_ORIGIN_LOCATION_FILE),
        span3.getTags().keySet().toString());
    assertFalse(
        span3.getTags().containsKey(DDTags.DD_CODE_ORIGIN_LOCATION_SNAPSHOT_ID),
        span3.getTags().keySet().toString());
  }

  private void invoke(AgentSpan span, CodeOriginProbe probe) {
    tracerAPI.activateSpan(span, ScopeSource.MANUAL);
    probe.evaluate(EMPTY_CAPTURING_CONTEXT, LogStatus.EMPTY_LOG_STATUS, MethodLocation.EXIT);
    probe.commit(EMPTY_CAPTURING_CONTEXT, EMPTY_CAPTURING_CONTEXT, emptyList());
  }

  private AgentSpan createProbePath1() {
    return createProbePath2();
  }

  private AgentSpan createProbePath1a() {
    return createProbePath3();
  }

  private AgentSpan createProbePath2() {
    return createProbePath3();
  }

  private AgentSpan createProbePath3() {
    AgentSpan span = tracerAPI.startSpan("span debugger test", "span" + (++count));
    try {

      if (count % 2 == 1) {
        CodeOriginInfo.entry(span, getClass().getDeclaredMethod("createProbePath3"));
      } else {
        CodeOriginInfo.exit(span);
      }

      return span;
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
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
}
