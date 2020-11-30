package datadog.trace.agent.tooling.log;

import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.Tracer;
import datadog.trace.api.WithGlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.context.ScopeListener;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * A scope listener that receives the MDC/ThreadContext put and receive methods and update the trace
 * and span reference anytime a new scope is activated or closed.
 */
@Slf4j
public class LogContextScopeListener implements ScopeListener, WithGlobalTracer.Callback {

  public static void add(final String name, final Method putMethod, final Method removeMethod) {
    final LogContextScopeListener listener =
        new LogContextScopeListener(name, putMethod, removeMethod);
    WithGlobalTracer.registerOrExecute(listener);
  }

  /** A reference to the log context method that sets a new attribute in the log context */
  private final Method putMethod;

  /** A reference to the log context method that removes an attribute from the log context */
  private final Method removeMethod;

  /** The name of the logging instrumentation that this listener belongs to */
  private final String name;

  private LogContextScopeListener(
      final String name, final Method putMethod, final Method removeMethod) {
    this.putMethod = putMethod;
    this.removeMethod = removeMethod;
    this.name = name;
  }

  @Override
  public void afterScopeActivated() {
    try {
      putMethod.invoke(
          null, CorrelationIdentifier.getTraceIdKey(), CorrelationIdentifier.getTraceId());
      putMethod.invoke(
          null, CorrelationIdentifier.getSpanIdKey(), CorrelationIdentifier.getSpanId());
    } catch (final Exception e) {
      log.debug("Exception setting log context context", e);
    }
  }

  @Override
  public void afterScopeClosed() {
    try {
      removeMethod.invoke(null, CorrelationIdentifier.getTraceIdKey());
      removeMethod.invoke(null, CorrelationIdentifier.getSpanIdKey());
    } catch (final Exception e) {
      log.debug("Exception removing log context context", e);
    }
  }

  static final Map<String, String> LOG_CONTEXT_DD_TAGS;

  static {
    Map<String, String> logContextDDTags = new HashMap<>();
    logContextDDTags.put(Tags.DD_SERVICE, Config.get().getServiceName());
    final Map<String, String> mergedSpanTags = Config.get().getMergedSpanTags();
    String version = "";
    String env = "";
    if (mergedSpanTags != null) {
      version = mergedSpanTags.get("version");
      if (version == null) {
        version = "";
      }
      env = mergedSpanTags.get("env");
      if (env == null) {
        env = "";
      }
    }
    logContextDDTags.put(Tags.DD_VERSION, version);
    logContextDDTags.put(Tags.DD_ENV, env);
    LOG_CONTEXT_DD_TAGS = Collections.unmodifiableMap(logContextDDTags);
  }

  public static void addDDTagsToMDC(final Method putMethod)
      throws InvocationTargetException, IllegalAccessException {
    for (final Map.Entry<String, String> e : LOG_CONTEXT_DD_TAGS.entrySet()) {
      putMethod.invoke(null, e.getKey(), e.getValue());
    }
  }

  @Override
  public String toString() {
    return "LogContextScopeListener(" + name + ")";
  }

  @Override
  public void withTracer(Tracer tracer) {
    tracer.addScopeListener(this);
  }
}
