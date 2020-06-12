package datadog.trace.agent.tooling.log;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.context.ScopeListener;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * A scope listener that receives the MDC/ThreadContext put and receive methods and update the trace
 * and span reference anytime a new scope is activated or closed.
 */
@Slf4j
public class LogContextScopeListener implements ScopeListener {

  /** A reference to the log context method that sets a new attribute in the log context */
  private final Method putMethod;

  /** A reference to the log context method that removes an attribute from the log context */
  private final Method removeMethod;

  public LogContextScopeListener(final Method putMethod, final Method removeMethod) {
    this.putMethod = putMethod;
    this.removeMethod = removeMethod;
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
    if (activeSpan() != null) {
      afterScopeActivated();
      return;
    }
    try {
      removeMethod.invoke(null, CorrelationIdentifier.getTraceIdKey());
      removeMethod.invoke(null, CorrelationIdentifier.getSpanIdKey());
    } catch (final Exception e) {
      log.debug("Exception removing log context context", e);
    }
  }

  public static void addDDTagsToMDC(final Method putMethod)
      throws InvocationTargetException, IllegalAccessException {
    putMethod.invoke(null, Tags.DD_SERVICE, Config.get().getServiceName());
    {
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
      putMethod.invoke(null, Tags.DD_VERSION, version);
      putMethod.invoke(null, Tags.DD_ENV, env);
    }
  }
}
