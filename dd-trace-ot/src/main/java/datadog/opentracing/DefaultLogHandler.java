package datadog.opentracing;

import static datadog.trace.api.DDTags.ERROR_MSG;
import static io.opentracing.log.Fields.ERROR_OBJECT;
import static io.opentracing.log.Fields.MESSAGE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** The default implementation of the LogHandler. */
@Slf4j
public class DefaultLogHandler implements LogHandler {
  @Override
  public void log(final Map<String, ?> fields, final AgentSpan span) {
    extractError(fields, span);
    log.debug("`log` method is not implemented. Doing nothing");
  }

  @Override
  public void log(
      final long timestampMicroseconds, final Map<String, ?> fields, final AgentSpan span) {
    extractError(fields, span);
    log.debug("`log` method is not implemented. Doing nothing");
  }

  @Override
  public void log(final String event, final AgentSpan span) {
    log.debug("`log` method is not implemented. Provided log: {}", event);
  }

  @Override
  public void log(final long timestampMicroseconds, final String event, final AgentSpan span) {
    log.debug("`log` method is not implemented. Provided log: {}", event);
  }

  private void extractError(final Map<String, ?> map, final AgentSpan span) {
    if (map.get(ERROR_OBJECT) instanceof Throwable) {
      final Throwable error = (Throwable) map.get(ERROR_OBJECT);
      span.addThrowable(error);
    } else if (map.get(MESSAGE) instanceof String) {
      span.setTag(ERROR_MSG, (String) map.get(MESSAGE));
    }
  }
}
