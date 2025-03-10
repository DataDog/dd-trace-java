package com.datadog.debugger.trigger;

import static com.datadog.debugger.el.DSL.*;
import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static utils.InstrumentationTestHelper.compileAndLoadClass;
import static utils.TestHelper.setFieldInConfig;

import com.datadog.debugger.agent.CapturingTestBase;
import com.datadog.debugger.agent.Configuration;
import com.datadog.debugger.agent.MockSampler;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.probe.Sampling;
import com.datadog.debugger.probe.TriggerProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.TestTraceInterceptor;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.propagation.PropagationTags;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import org.joor.Reflect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TriggerProbeTest extends CapturingTestBase {
  private static final ProbeId TRIGGER_PROBE_ID1 = new ProbeId("trigger probe 1", 0);
  private static final String TRIGGER_PROBE_SESSION_ID = "trigger probe sessionID";

  private TestTraceInterceptor traceInterceptor;

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
    setFieldInConfig(Config.get(), "distributedDebuggerEnabled", true);
  }

  @Test
  public void conditions() throws IOException, URISyntaxException {
    final String className = "com.datadog.debugger.TriggerProbe02";
    TriggerProbe probe1 =
        createTriggerProbe(
            TRIGGER_PROBE_ID1,
            TRIGGER_PROBE_SESSION_ID,
            className,
            "entry",
            "(int)",
            new ProbeCondition(when(lt(ref("value"), value(25))), "value < 25"),
            new Sampling(10.0));
    installProbes(Configuration.builder().setService(SERVICE_NAME).add(probe1).build());
    Class<?> testClass = compileAndLoadClass(className);
    for (int i = 0; i < 100; i++) {
      Reflect.onClass(testClass).call("main", i).get();
    }
    List<List<? extends MutableSpan>> allTraces = traceInterceptor.getAllTraces();
    long count =
        allTraces.stream()
            .map(span -> span.get(0))
            .filter(
                span -> {
                  DDSpan ddSpan = (DDSpan) span;
                  PropagationTags tags = ddSpan.context().getPropagationTags();
                  return (TRIGGER_PROBE_SESSION_ID + ":1").equals(tags.getDebugPropagation());
                })
            .count();
    assertEquals(100, allTraces.size(), "actual traces: " + allTraces.size());
    assertTrue(count <= 25, "Should have at most 25 debug sessions.  found: " + count);
  }

  private static TriggerProbe createTriggerProbe(
      ProbeId id,
      String sessionId,
      String typeName,
      String methodName,
      String signature,
      ProbeCondition probeCondition,
      Sampling sampling) {
    return new TriggerProbe(id, Where.of(typeName, methodName, signature))
        .setSessionId(sessionId)
        .setProbeCondition(probeCondition)
        .setSampling(sampling);
  }

  @Test
  public void cooldown() throws IOException, URISyntaxException {
    try {
      MockSampler sampler = new MockSampler();
      ProbeRateLimiter.setSamplerSupplier(value -> sampler);

      final String className = "com.datadog.debugger.TriggerProbe01";
      TriggerProbe probe1 =
          createTriggerProbe(
              TRIGGER_PROBE_ID1,
              TRIGGER_PROBE_SESSION_ID,
              className,
              "entry",
              "()",
              null,
              new Sampling(10, 10.0));
      installProbes(Configuration.builder().setService(SERVICE_NAME).add(probe1).build());
      Class<?> testClass = compileAndLoadClass(className);
      int runs = 10000;
      for (int i = 0; i < runs; i++) {
        Reflect.onClass(testClass).call("main", "").get();
      }

      assertEquals(1, sampler.getCallCount());
      List<List<? extends MutableSpan>> allTraces = traceInterceptor.getAllTraces();
      assertEquals(runs, allTraces.size(), "actual traces: " + allTraces.size());

      long debugSessions =
          allTraces.stream()
              .map(span -> span.get(0))
              .filter(
                  span -> {
                    DDSpan ddSpan = (DDSpan) span;
                    PropagationTags tags = ddSpan.context().getPropagationTags();
                    return (TRIGGER_PROBE_SESSION_ID + ":1").equals(tags.getDebugPropagation());
                  })
              .count();
      assertEquals(1, debugSessions, "Should only have 1 debug session.  found: " + debugSessions);

      long tagged =
          allTraces.stream()
              .flatMap(Collection::stream)
              .filter(
                  span ->
                      span.getTag(format("_dd.ld.probe_id.%s", TRIGGER_PROBE_ID1.getId())) != null)
              .count();
      assertEquals(1, tagged, "Should only have 1 tagged span.  found: " + tagged);
    } finally {
      ProbeRateLimiter.setSamplerSupplier(null);
    }
  }

  @Test
  public void badCondition() throws IOException, URISyntaxException {
    String className = "com.datadog.debugger.TriggerProbe02";
    TriggerProbe probe1 =
        createTriggerProbe(
            TRIGGER_PROBE_ID1,
            TRIGGER_PROBE_SESSION_ID,
            className,
            "entry",
            "(int)",
            new ProbeCondition(when(lt(ref("limit"), value(25))), "limit < 25"),
            new Sampling(10.0));

    installProbes(Configuration.builder().setService(SERVICE_NAME).add(probe1).build());
    Class<?> testClass = compileAndLoadClass(className);
    Reflect.onClass(testClass).call("main", 0).get();
    verify(probeStatusSink)
        .addError(
            eq(TRIGGER_PROBE_ID1),
            eq(new EvaluationException("Cannot find symbol: limit", "limit")));
  }

  @Test
  public void debuggerDisabled() throws IOException, URISyntaxException {
    boolean original = Config.get().isDistributedDebuggerEnabled();
    try {
      setFieldInConfig(Config.get(), "distributedDebuggerEnabled", false);

      MockSampler sampler = new MockSampler();
      ProbeRateLimiter.setSamplerSupplier(value -> sampler);

      final String className = "com.datadog.debugger.TriggerProbe02";
      TriggerProbe probe1 =
          createTriggerProbe(
              TRIGGER_PROBE_ID1,
              TRIGGER_PROBE_SESSION_ID,
              className,
              "entry",
              "(int)",
              new ProbeCondition(when(lt(ref("value"), value(25))), "value < 25"),
              new Sampling(10.0));
      installProbes(Configuration.builder().setService(SERVICE_NAME).add(probe1).build());
      Class<?> testClass = compileAndLoadClass(className);
      Reflect.onClass(testClass).call("main", 0).get();

      assertEquals(0, sampler.getCallCount());
    } finally {
      setFieldInConfig(Config.get(), "distributedDebuggerEnabled", original);
      ProbeRateLimiter.setSamplerSupplier(null);
    }
  }

  @Test
  public void sampling() throws IOException, URISyntaxException {
    try {
      MockSampler sampler = new MockSampler();
      ProbeRateLimiter.setSamplerSupplier(value -> sampler);

      final String className = "com.datadog.debugger.TriggerProbe01";
      TriggerProbe probe1 =
          createTriggerProbe(
              TRIGGER_PROBE_ID1,
              TRIGGER_PROBE_SESSION_ID,
              className,
              "entry",
              "()",
              null,
              new Sampling(10.0));
      Configuration config = Configuration.builder().setService(SERVICE_NAME).add(probe1).build();
      installProbes(config);
      Class<?> testClass = compileAndLoadClass(className);
      for (int i = 0; i < 100; i++) {
        Reflect.onClass(testClass).call("main", "").get();
      }

      assertTrue(sampler.getCallCount() != 0);
    } finally {
      ProbeRateLimiter.setSamplerSupplier(null);
    }
  }

  @Test
  public void noSampling() throws IOException, URISyntaxException {
    try {
      MockSampler sampler = new MockSampler();
      ProbeRateLimiter.setSamplerSupplier(value -> sampler);

      final String className = "com.datadog.debugger.TriggerProbe01";
      TriggerProbe probe1 =
          createTriggerProbe(
              TRIGGER_PROBE_ID1, TRIGGER_PROBE_SESSION_ID, className, "entry", "()", null, null);
      Configuration config = Configuration.builder().setService(SERVICE_NAME).add(probe1).build();
      installProbes(config);
      Class<?> testClass = compileAndLoadClass(className);
      for (int i = 0; i < 100; i++) {
        Reflect.onClass(testClass).call("main", "").get();
      }

      assertTrue(sampler.getCallCount() != 0);
    } finally {
      ProbeRateLimiter.setSamplerSupplier(null);
    }
  }
}
