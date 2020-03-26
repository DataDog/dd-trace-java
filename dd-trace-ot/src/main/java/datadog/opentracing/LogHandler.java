package datadog.opentracing;

import java.util.Map;

public interface LogHandler {

  /**
   * Handles the log implementation in the Span.
   *
   * @param fields key:value log fields. Tracer implementations should support String, numeric, and
   *     boolean values; some may also support arbitrary Objects.
   * @param span from which the call was made
   */
  void log(Map<String, ?> fields, DDSpan span);

  /**
   * Handles the log implementation in the Span.
   *
   * @param timestampMicroseconds The explicit timestamp for the log record. Must be greater than or
   *     equal to the Span's start timestamp.
   * @param fields key:value log fields. Tracer implementations should support String, numeric, and
   * @param span from which the call was made
   */
  void log(long timestampMicroseconds, Map<String, ?> fields, DDSpan span);

  /**
   * Handles the log implementation in the Span..
   *
   * @param event the event value; often a stable identifier for a moment in the Span lifecycle
   * @param span from which the call was made
   */
  void log(String event, DDSpan span);

  /**
   * Handles the log implementation in the Span.
   *
   * @param timestampMicroseconds The explicit timestamp for the log record. Must be greater than or
   *     equal to the Span's start timestamp.
   * @param event the event value; often a stable identifier for a moment in the Span lifecycle
   * @param span from which the call was made
   */
  void log(long timestampMicroseconds, String event, DDSpan span);
}
