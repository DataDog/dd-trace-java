package com.datadog.debugger.agent;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.util.SerializerWithLimits;
import com.datadog.debugger.util.StringTokenWriter;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class LogMessageTemplateBuilder {
  /**
   * Serialization limits for log messages. Most values are lower than snapshot because you can
   * directly reference values that are in your interest with Expression Language:
   * obj.field.deepfield or array[1001]
   */
  private static final Limits LIMITS = new Limits(1, 3, 255, 5);

  private static final Duration TIME_OUT = Duration.of(100, ChronoUnit.MILLIS);

  private final List<LogProbe.Segment> segments;
  private final List<Snapshot.EvaluationError> evaluationErrors = new ArrayList<>();

  public LogMessageTemplateBuilder(List<LogProbe.Segment> segments) {
    this.segments = segments;
  }

  public List<Snapshot.EvaluationError> getEvaluationErrors() {
    return evaluationErrors;
  }

  public String evaluate(Snapshot.CapturedContext context) {
    if (segments == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (LogProbe.Segment segment : segments) {
      ValueScript parsedExr = segment.getParsedExpr();
      if (segment.getStr() != null) {
        sb.append(segment.getStr());
      } else {
        if (parsedExr != null) {
          try {
            Value<?> result = parsedExr.execute(context);
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
    return sb.toString();
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
