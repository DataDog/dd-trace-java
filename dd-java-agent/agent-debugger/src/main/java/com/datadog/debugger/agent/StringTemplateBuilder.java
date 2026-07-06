package com.datadog.debugger.agent;

import static com.datadog.debugger.util.ValueScriptHelper.serializeValue;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.RedactedException;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.probe.LogProbe;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.util.Redaction;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StringTemplateBuilder {
  private static final Logger LOGGER = LoggerFactory.getLogger(StringTemplateBuilder.class);

  /**
   * Serialization limits for log messages. Most values are lower than snapshot because you can
   * directly reference values that are in your interest with Expression Language:
   * obj.field.deepfield or array[1001]
   */
  private final List<LogProbe.Segment> segments;

  private final Limits limits;

  public StringTemplateBuilder(List<LogProbe.Segment> segments, Limits limits) {
    this.segments = segments;
    this.limits = limits;
  }

  public String evaluate(CapturedContext context, LogProbe.LogStatus status) {
    if (segments == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    // Only one timeout for all expressions
    Duration timeout = Duration.ofMillis(Config.get().getDynamicInstrumentationEvalTimeout());
    TimeoutChecker timeoutChecker = TimeoutChecker.create(Config.get(), timeout);
    for (LogProbe.Segment segment : segments) {
      ValueScript parsedExr = segment.getParsedExpr();
      if (segment.getStr() != null) {
        sb.append(segment.getStr());
      } else {
        if (parsedExr != null) {
          try {
            Value<?> result = parsedExr.execute(context, timeoutChecker);
            if (result.isUndefined()) {
              sb.append(result.getValue());
            } else if (result.isNull()) {
              sb.append("null");
            } else {
              serializeValue(
                  sb, segment.getParsedExpr().getDsl(), result.getValue(), status, limits);
            }
          } catch (EvaluationException ex) {
            handleException(status, ex, ex.getExpr(), sb);
          } catch (Exception ex) {
            // catch all for unexpected exceptions
            handleException(status, ex, segment.getExpr(), sb);
          }
          if (!status.getErrors().isEmpty()) {
            status.setLogTemplateErrors(true);
          }
        }
      }
    }
    return sb.toString();
  }

  private static void handleException(
      LogProbe.LogStatus status, Exception ex, String expr, StringBuilder sb) {
    status.addError(new EvaluationError(expr, ex.getMessage()));
    String msg = ex instanceof RedactedException ? Redaction.REDACTED_VALUE : ex.getMessage();
    sb.append('{').append(msg).append('}');
  }
}
