package com.datadog.debugger.agent;

import static com.datadog.debugger.util.ValueScriptHelper.serializeValue;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.probe.LogProbe;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogMessageTemplateBuilder {
  private static final Logger LOGGER = LoggerFactory.getLogger(LogMessageTemplateBuilder.class);
  /**
   * Serialization limits for log messages. Most values are lower than snapshot because you can
   * directly reference values that are in your interest with Expression Language:
   * obj.field.deepfield or array[1001]
   */
  private final List<LogProbe.Segment> segments;

  public LogMessageTemplateBuilder(List<LogProbe.Segment> segments) {
    this.segments = segments;
  }

  public String evaluate(CapturedContext context, LogProbe.LogStatus status) {
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
              serializeValue(sb, segment.getParsedExpr().getDsl(), result.getValue(), status);
            }
          } catch (EvaluationException ex) {
            LOGGER.debug("Evaluation error: ", ex);
            status.addError(new EvaluationError(ex.getExpr(), ex.getMessage()));
            status.setLogTemplateErrors(true);
            sb.append("{").append(ex.getMessage()).append("}");
          }
        }
      }
    }
    return sb.toString();
  }
}
