package datadog.smoketest;

import static com.datadog.debugger.el.DSL.eq;
import static com.datadog.debugger.el.DSL.gt;
import static com.datadog.debugger.el.DSL.index;
import static com.datadog.debugger.el.DSL.lt;
import static com.datadog.debugger.el.DSL.not;
import static com.datadog.debugger.el.DSL.nullValue;
import static com.datadog.debugger.el.DSL.ref;
import static com.datadog.debugger.el.DSL.value;
import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.expressions.BooleanExpression;
import com.datadog.debugger.probe.SpanDecorationProbe;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.util.NonRetryable;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.DisabledIf;

@NonRetryable
public class SpanDecorationProbesIntegrationTests extends ServerAppDebuggerIntegrationTest {

  @BeforeEach
  @Override
  void setup(TestInfo testInfo) throws Exception {
    super.setup(testInfo);
    appUrl = startAppAndAndGetUrl();
  }

  @Override
  protected ProcessBuilder createProcessBuilder(Path logFilePath, String... params) {
    List<String> commandParams = getDebuggerCommandParams();
    commandParams.add("-Ddd.trace.enabled=true"); // explicitly enable tracer
    return ProcessBuilderHelper.createProcessBuilder(
        commandParams, logFilePath, getAppClass(), params);
  }

  @Test
  @DisplayName("testMethodSimpleTagNoCondition")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  void testMethodSimpleTagNoCondition() throws Exception {
    SpanDecorationProbe spanDecorationProbe =
        SpanDecorationProbe.builder()
            .probeId(PROBE_ID)
            .where(TEST_APP_CLASS_NAME, TRACED_METHOD_NAME)
            .decorate(createDecoration("tag1", "{argStr}"))
            .targetSpan(SpanDecorationProbe.TargetSpan.ACTIVE)
            .build();
    addProbe(spanDecorationProbe);
    waitForInstrumentation(appUrl);
    execute(appUrl, TRACED_METHOD_NAME);
    AtomicBoolean traceReceived = new AtomicBoolean();
    registerTraceListener(
        decodedTrace -> {
          for (DecodedSpan span : decodedTrace.getSpans()) {
            if (isTracedFullMethodSpan(span)) {
              assertEquals("foobar", span.getMeta().get("tag1"));
              assertEquals(PROBE_ID.getId(), span.getMeta().get("_dd.di.tag1.probe_id"));
              traceReceived.set(true);
            }
          }
        });
    processRequests(
        traceReceived::get, () -> String.format("timeout traceReceived=%s", traceReceived.get()));
  }

  @Test
  @DisplayName("testMethodMultiTagsMultiConditions")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  void testMethodMultiTagsMultiConditions() throws Exception {
    List<SpanDecorationProbe.Decoration> decorations =
        Arrays.asList(
            createDecoration(
                not(eq(ref("argStr"), nullValue())), "argStr != null", "tag1", "{argStr}"),
            createDecoration(lt(ref("argInt"), value(0)), "argInt < 0", "tag2", "{argInt}"),
            createDecoration(
                gt(ref("argDouble"), value(3.14)),
                "argDouble > 3.14",
                "tag3",
                "Above Pi: {argDouble}"),
            createDecoration(
                eq(index(ref("argMap"), value("key2")), value("val2")),
                "argMap['key2'] == 'val2'",
                "tag4",
                "{argMap['key2']}"));
    SpanDecorationProbe spanDecorationProbe =
        SpanDecorationProbe.builder()
            .probeId(PROBE_ID)
            .where(TEST_APP_CLASS_NAME, TRACED_METHOD_NAME)
            .decorate(decorations)
            .targetSpan(SpanDecorationProbe.TargetSpan.ACTIVE)
            .build();
    addProbe(spanDecorationProbe);
    waitForInstrumentation(appUrl);
    execute(appUrl, TRACED_METHOD_NAME);
    AtomicBoolean traceReceived = new AtomicBoolean();
    registerTraceListener(
        decodedTrace -> {
          for (DecodedSpan span : decodedTrace.getSpans()) {
            if (isTracedFullMethodSpan(span)) {
              assertEquals("foobar", span.getMeta().get("tag1"));
              assertFalse(span.getMeta().containsKey("tag2"));
              assertEquals("Above Pi: 3.42", span.getMeta().get("tag3"));
              assertEquals("val2", span.getMeta().get("tag4"));
              traceReceived.set(true);
            }
          }
        });
    processRequests(
        traceReceived::get, () -> String.format("timeout traceReceived=%s", traceReceived.get()));
  }

  @Test
  @DisplayName("testMethodSimpleTagValueError")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testMethodSimpleTagValueError() throws Exception {
    SpanDecorationProbe spanDecorationProbe =
        SpanDecorationProbe.builder()
            .probeId(PROBE_ID)
            .where(TEST_APP_CLASS_NAME, TRACED_METHOD_NAME)
            .decorate(createDecoration("tag1", "{invalidArg}"))
            .targetSpan(SpanDecorationProbe.TargetSpan.ACTIVE)
            .build();
    addProbe(spanDecorationProbe);
    waitForInstrumentation(appUrl);
    execute(appUrl, TRACED_METHOD_NAME);
    AtomicBoolean snapshotTest = new AtomicBoolean(false);
    AtomicBoolean spanTest = new AtomicBoolean(false);
    registerSnapshotListener(
        snapshot -> {
          assertEquals(
              "Cannot find symbol: invalidArg", snapshot.getEvaluationErrors().get(0).getMessage());
          snapshotTest.set(true);
        });
    registerTraceListener(
        decodedTrace -> {
          for (DecodedSpan span : decodedTrace.getSpans()) {
            if (isTracedFullMethodSpan(span)) {
              assertFalse(span.getMeta().containsKey("tag1"));
              assertEquals(
                  "Cannot find symbol: invalidArg",
                  span.getMeta().get("_dd.di.tag1.evaluation_error"));
              spanTest.set(true);
            }
          }
        });
    processRequests(
        () -> snapshotTest.get() && spanTest.get(),
        () ->
            String.format(
                "timeout snapshotTest=%s spanTest=%s", snapshotTest.get(), spanTest.get()));
  }

  @Test
  @DisplayName("testMethodSimpleTagConditionError")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testMethodSimpleTagConditionError() throws Exception {
    SpanDecorationProbe spanDecorationProbe =
        SpanDecorationProbe.builder()
            .probeId(PROBE_ID)
            .where(TEST_APP_CLASS_NAME, TRACED_METHOD_NAME)
            .decorate(
                createDecoration(
                    not(eq(ref("invalidArg"), nullValue())),
                    "invalidArg != null",
                    "tag1",
                    "{argStr}"))
            .targetSpan(SpanDecorationProbe.TargetSpan.ACTIVE)
            .build();
    addProbe(spanDecorationProbe);
    waitForInstrumentation(appUrl);
    execute(appUrl, TRACED_METHOD_NAME);
    AtomicBoolean snapshotTest = new AtomicBoolean(false);
    AtomicBoolean spanTest = new AtomicBoolean(false);
    registerSnapshotListener(
        snapshot -> {
          assertEquals(
              "Cannot find symbol: invalidArg", snapshot.getEvaluationErrors().get(0).getMessage());
          snapshotTest.set(true);
        });
    registerTraceListener(
        decodedTrace -> {
          for (DecodedSpan span : decodedTrace.getSpans()) {
            if (isTracedFullMethodSpan(span)) {
              assertFalse(span.getMeta().containsKey("tag1"));
              spanTest.set(true);
            }
          }
        });
    processRequests(
        () -> snapshotTest.get() && spanTest.get(),
        () ->
            String.format(
                "timeout snapshotTest=%s spanTest=%s", snapshotTest.get(), spanTest.get()));
  }

  @Test
  @DisplayName("testMethodMultiTagValueError")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testMethodMultiTagValueError() throws Exception {
    List<SpanDecorationProbe.Decoration> decorations =
        Arrays.asList(
            createDecoration("tag1", "{invalidArg}"), createDecoration("tag2", "{invalidArg2}"));
    SpanDecorationProbe spanDecorationProbe =
        SpanDecorationProbe.builder()
            .probeId(PROBE_ID)
            .where(TEST_APP_CLASS_NAME, TRACED_METHOD_NAME)
            .decorate(decorations)
            .targetSpan(SpanDecorationProbe.TargetSpan.ACTIVE)
            .build();
    addProbe(spanDecorationProbe);
    waitForInstrumentation(appUrl);
    execute(appUrl, TRACED_METHOD_NAME);
    AtomicBoolean snapshotTest = new AtomicBoolean(false);
    AtomicBoolean spanTest = new AtomicBoolean(false);
    registerSnapshotListener(
        snapshot -> {
          List<EvaluationError> evalErrors = snapshot.getEvaluationErrors();
          assertEquals("Cannot find symbol: invalidArg", evalErrors.get(0).getMessage());
          assertEquals("Cannot find symbol: invalidArg2", evalErrors.get(1).getMessage());
          snapshotTest.set(true);
        });
    registerTraceListener(
        decodedTrace -> {
          for (DecodedSpan span : decodedTrace.getSpans()) {
            if (isTracedFullMethodSpan(span)) {
              assertFalse(span.getMeta().containsKey("tag1"));
              assertFalse(span.getMeta().containsKey("tag2"));
              assertEquals(
                  "Cannot find symbol: invalidArg",
                  span.getMeta().get("_dd.di.tag1.evaluation_error"));
              assertEquals(
                  "Cannot find symbol: invalidArg2",
                  span.getMeta().get("_dd.di.tag2.evaluation_error"));
              spanTest.set(true);
            }
          }
        });
    processRequests(
        () -> snapshotTest.get() && spanTest.get(),
        () ->
            String.format(
                "timeout snapshotTest=%s spanTest=%s", snapshotTest.get(), spanTest.get()));
  }

  @Test
  @DisplayName("testSamplingSpanDecoration")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testSamplingSpanDecoration() throws Exception {
    SpanDecorationProbe spanDecorationProbe =
        SpanDecorationProbe.builder()
            .probeId(PROBE_ID)
            .where(TEST_APP_CLASS_NAME, TRACED_METHOD_NAME)
            .decorate(createDecoration("tag1", "staticText"))
            .targetSpan(SpanDecorationProbe.TargetSpan.ACTIVE)
            .build();
    addProbe(spanDecorationProbe);
    waitForInstrumentation(appUrl);
    execute(appUrl, "loopingTracedMethod", "100");
    AtomicInteger count = new AtomicInteger();
    registerTraceListener(
        decodedTrace -> {
          for (DecodedSpan span : decodedTrace.getSpans()) {
            if (isTracedFullMethodSpan(span)) {
              if (span.getMeta().containsKey("tag1")) {
                count.incrementAndGet();
              }
            }
          }
        });
    processRequests(() -> count.get() >= 100, () -> String.format("timeout count=%d", count.get()));
  }

  private SpanDecorationProbe.Decoration createDecoration(String tagName, String valueDsl) {
    List<SpanDecorationProbe.Tag> tags =
        Arrays.asList(
            new SpanDecorationProbe.Tag(
                tagName, new SpanDecorationProbe.TagValue(valueDsl, parseTemplate(valueDsl))));
    return new SpanDecorationProbe.Decoration(null, tags);
  }

  private SpanDecorationProbe.Decoration createDecoration(
      BooleanExpression expression, String dsl, String tagName, String valueDsl) {
    List<SpanDecorationProbe.Tag> tags =
        Arrays.asList(
            new SpanDecorationProbe.Tag(
                tagName, new SpanDecorationProbe.TagValue(valueDsl, parseTemplate(valueDsl))));
    return new SpanDecorationProbe.Decoration(new ProbeCondition(DSL.when(expression), dsl), tags);
  }
}
