package datadog.trace.instrumentation.opentracing;

import static datadog.trace.api.DDTags.ERROR_MSG;
import static io.opentracing.log.Fields.ERROR_OBJECT;
import static io.opentracing.log.Fields.EVENT;
import static io.opentracing.log.Fields.MESSAGE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The default implementation of the LogHandler. */
public class DefaultLogHandler implements LogHandler {

  private static final Logger log = LoggerFactory.getLogger(DefaultLogHandler.class);

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

  private boolean isErrorSpan(final Map<String, ?> map, final AgentSpan span) {
    final String event = map.get(EVENT) instanceof String ? (String) map.get(EVENT) : "";
    return span.isError() || event.equalsIgnoreCase("error");
  }

  private void extractError(final Map<String, ?> map, final AgentSpan span) {
    if (map.get(ERROR_OBJECT) instanceof Throwable) {
      final Throwable error = (Throwable) map.get(ERROR_OBJECT);
      span.addThrowable(error);
    } else if (isErrorSpan(map, span) && map.get(MESSAGE) instanceof String) {
      span.setTag(ERROR_MSG, (String) map.get(MESSAGE));
    }
  }
}
