package datadog.trace.instrumentation.log4j2;

import java.util.Map;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;

/** Renders absent IDs as zero without nested replacement converters on early Log4j2 releases. */
public final class CorrelationIdPatternConverter extends LogEventPatternConverter {
  private final String key;

  private CorrelationIdPatternConverter(String key) {
    super("Datadog correlation ID", "mdc");
    this.key = key;
  }

  public static CorrelationIdPatternConverter newInstance(String[] options) {
    if (options == null
        || options.length != 1
        || !("dd.trace_id".equals(options[0]) || "dd.span_id".equals(options[0]))) {
      return null;
    }
    return new CorrelationIdPatternConverter(options[0]);
  }

  @Override
  public void format(LogEvent event, StringBuilder output) {
    Map<String, String> context = event.getContextMap();
    String value = context == null ? null : context.get(key);
    output.append(value == null || value.isEmpty() ? "0" : value);
  }
}
