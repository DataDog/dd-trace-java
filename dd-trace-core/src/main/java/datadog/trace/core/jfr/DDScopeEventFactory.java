package datadog.trace.core.jfr;

import datadog.trace.core.DDSpanContext;

/** Factory that produces scope events */
public interface DDScopeEventFactory {

  /**
   * Create new scope event for given context.
   *
   * @param context span context.
   * @return scope event instance
   */
  DDScopeEvent create(final DDSpanContext context);
}
