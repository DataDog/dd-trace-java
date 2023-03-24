package com.datadog.debugger.agent;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.util.SerializerWithLimits;
import com.datadog.debugger.util.StringTokenWriter;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.SummaryBuilder;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class LogMessageTemplateSummaryBuilder implements SummaryBuilder {
  /**
   * Serialization limits for log messages. Most values are lower than snapshot because you can
   * directly reference values that are in your interest with Expression Language:
   * obj.field.deepfield or array[1001]
   */
  private static final Limits LIMITS = new Limits(1, 3, 255, 5);

  private static final Duration TIME_OUT = Duration.of(100, ChronoUnit.MILLIS);

  private final LogProbe logProbe;
  private final List<Snapshot.EvaluationError> evaluationErrors = new ArrayList<>();
  private String message;

  public LogMessageTemplateSummaryBuilder(LogProbe logProbe) {
    this.logProbe = logProbe;
  }

  @Override
  public void addEntry(Snapshot.CapturedContext entry) {
    executeExpressions(entry);
  }

  @Override
  public void addExit(Snapshot.CapturedContext exit) {
    executeExpressions(exit);
  }

  @Override
  public void addLine(Snapshot.CapturedContext line) {
    executeExpressions(line);
  }

  @Override
  public void addStack(List<CapturedStackFrame> stack) {}

  @Override
  public String build() {
    if (message == null) {
      return "This is a dynamically created log message.";
    }
    return message;
  }

  @Override
  public List<Snapshot.EvaluationError> getEvaluationErrors() {
    return evaluationErrors;
  }

  private void executeExpressions(Snapshot.CapturedContext entry) {
    StringBuilder sb = new StringBuilder();
    if (logProbe.getSegments() == null) {
      return;
    }
    for (LogProbe.Segment segment : logProbe.getSegments()) {
      ValueScript parsedExr = segment.getParsedExpr();
      if (segment.getStr() != null) {
        sb.append(segment.getStr());
      } else {
        if (parsedExr != null) {
          try {
            Value<?> result = parsedExr.execute(entry);
            if (result.isUndefined()) {
              sb.append(result.getValue());
            } else if (result.isNull()) {
              sb.append("null");
            } else {
              serializeValue(sb, segment.getParsedExpr().getDsl(), result.getValue());
            }
          } catch (RuntimeException ex) {
            sb.append("{").append(ex.getMessage()).append("}");
          }
        }
      }
    }
    message = sb.toString();
  }

  private void serializeValue(StringBuilder sb, String expr, Object value) {
    SerializerWithLimits serializer =
        new SerializerWithLimits(
            new StringTokenWriter(sb, evaluationErrors), new TimeoutChecker(TIME_OUT));
    try {
      serializer.serialize(value, value != null ? value.getClass().getTypeName() : null, LIMITS);
    } catch (Exception ex) {
      evaluationErrors.add(new Snapshot.EvaluationError(expr, ex.toString()));
    }
  }
}
