package datadog.opentracing.jfr;

import datadog.opentracing.DDSpanContext;

/** Factory that produces span events */
public interface DDSpanEventFactory {

  /**
   * Create new span event for given context.
   *
   * @param context span context.
   * @return span event instance
   */
  DDSpanEvent create(final DDSpanContext context);
}
