package com.datadog.debugger.probe;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ValueScript;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LogProbeCaptureExpressionsUndefinedTest {
  @Test
  void captureExpressionUndefinedAddsError() {
    LogProbe.CaptureExpression ce =
        new LogProbe.CaptureExpression(
            "ce", new ValueScript(DSL.value(Values.UNDEFINED_OBJECT), "exprUndefined"), null);

    LogProbe probe =
        LogProbe.builder()
            .language("java")
            .probeId(ProbeId.newId())
            .template(null, emptyList())
            .captureExpressions(Arrays.asList(ce))
            .build();

    CapturedContext context = new CapturedContext();
    LogProbe.LogStatus status = new LogProbe.LogStatus(probe);

    probe.evaluate(context, status, MethodLocation.DEFAULT);

    assertTrue(status.hasLogTemplateErrors());
    assertEquals(1, status.getErrors().size());
    EvaluationError err = status.getErrors().get(0);
    assertEquals("exprUndefined", err.getExpr());
    assertEquals("UNDEFINED", err.getMessage());
  }
}
