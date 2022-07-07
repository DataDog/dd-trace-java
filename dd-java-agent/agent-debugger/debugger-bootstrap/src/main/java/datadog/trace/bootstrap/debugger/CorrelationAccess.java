package datadog.trace.bootstrap.debugger;

import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Gives access from instrumented code to collect current TraceId & SpanID */
public final class CorrelationAccess {
  private static final Logger log = LoggerFactory.getLogger(CorrelationAccess.class);

  // replace is to trick the relocate process from shadow gradle plugin
  // see https://github.com/johnrengelman/shadow/issues/305
  private static final String CORRELATION_IDENTIFIER_CLASSNAME =
      "datadog/trace/api/CorrelationIdentifier".replace('/', '.');

  private static volatile boolean REUSE_INSTANCE = true;

  private static class Singleton {
    private static final CorrelationAccess INSTANCE = new CorrelationAccess();
  }

  private final MethodHandle traceIdHandle;
  private final MethodHandle spanIdHandle;

  private CorrelationAccess() {
    MethodHandle traceIdHandle = null;
    MethodHandle spanIdHandle = null;
    // ignore correlations if tracer is not enabled
    if (ConfigProvider.getInstance().getBoolean(TraceInstrumentationConfig.TRACE_ENABLED, false)) {
      try {
        Class<?> clz =
            ClassLoader.getSystemClassLoader().loadClass(CORRELATION_IDENTIFIER_CLASSNAME);
        traceIdHandle =
            MethodHandles.publicLookup()
                .findStatic(clz, "getTraceId", MethodType.methodType(String.class));
        spanIdHandle =
            MethodHandles.publicLookup()
                .findStatic(clz, "getSpanId", MethodType.methodType(String.class));
      } catch (Throwable t) {
        if (log.isDebugEnabled()) {
          log.debug("Unable to initialize tracer correlation access: {}", t.toString());
        }
        traceIdHandle = null;
        spanIdHandle = null;
      }
    }
    this.traceIdHandle = traceIdHandle;
    this.spanIdHandle = spanIdHandle;
  }

  // Only for testing
  CorrelationAccess(MethodHandle traceIdHandle, MethodHandle spanIdHandle) {
    this.traceIdHandle = traceIdHandle;
    this.spanIdHandle = spanIdHandle;
  }

  public static CorrelationAccess instance() {
    return REUSE_INSTANCE ? Singleton.INSTANCE : new CorrelationAccess();
  }

  public boolean isAvailable() {
    return traceIdHandle != null && spanIdHandle != null;
  }

  public String getSpanId() {
    try {
      return isAvailable() ? (String) spanIdHandle.invokeExact() : null;
    } catch (Throwable t) {
      if (log.isDebugEnabled()) {
        log.debug("Unable to get tracer span id: {}", t.toString());
      }
    }
    return null;
  }

  public String getTraceId() {
    try {
      return isAvailable() ? (String) traceIdHandle.invokeExact() : null;
    } catch (Throwable t) {
      if (log.isDebugEnabled()) {
        log.debug("Unable to get tracer trace id: {}", t.toString());
      }
    }
    return null;
  }
}
