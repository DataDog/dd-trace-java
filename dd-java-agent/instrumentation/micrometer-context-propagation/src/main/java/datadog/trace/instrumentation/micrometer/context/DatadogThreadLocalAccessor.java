package datadog.trace.instrumentation.micrometer.context;

import datadog.context.Context;
import io.micrometer.context.ThreadLocalAccessor;

/**
 * A {@link ThreadLocalAccessor} that integrates Datadog tracing context with Micrometer's
 * context-propagation library.
 *
 * <p>This accessor enables automatic propagation of Datadog trace context across thread boundaries
 * when using Reactor with virtual threads or other reactive schedulers that leverage Micrometer's
 * context-propagation mechanism.
 *
 * <p>When Reactor's automatic context propagation is enabled (via
 * Hooks.enableAutomaticContextPropagation), this accessor ensures that the active Datadog trace
 * context (spans, scope) is properly saved before switching threads and restored on the new thread.
 */
public final class DatadogThreadLocalAccessor implements ThreadLocalAccessor<Context> {

  /** The key used to identify this accessor in the Micrometer context registry. */
  public static final String KEY = "datadog.trace.context";

  @Override
  public Object key() {
    return KEY;
  }

  @Override
  public Context getValue() {
    return Context.current();
  }

  @Override
  public void setValue(Context context) {
    context.swap();
  }

  @Override
  public void reset() {
    Context.root().swap();
  }
}
