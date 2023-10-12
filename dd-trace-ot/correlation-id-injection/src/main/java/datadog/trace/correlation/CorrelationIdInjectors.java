package datadog.trace.correlation;

import datadog.trace.api.internal.InternalTracer;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper class to enable correlation identifier injection. */
public final class CorrelationIdInjectors {
  private static final Logger log = LoggerFactory.getLogger(CorrelationIdInjectors.class);

  private CorrelationIdInjectors() {}

  /**
   * Register any applicable correlation identifier injectors.
   *
   * @param tracer The tracer to register the injectors to.
   */
  public static void register(InternalTracer tracer) {
    for (InjectorType type : InjectorType.values()) {
      type.register(tracer);
    }
  }

  @SuppressForbidden
  private static boolean isClassPresent(String className) {
    try {
      Class.forName(className);
      return true;
    } catch (ClassNotFoundException ignored) {
      return false;
    }
  }

  @SuppressForbidden
  private static void enableInjector(String className, InternalTracer tracer) {
    try {
      Class<?> injectorClass = Class.forName(className);
      injectorClass.getConstructor(InternalTracer.class).newInstance(tracer);
    } catch (ReflectiveOperationException e) {
      log.warn("Failed to enable injector {}.", className, e);
    }
  }

  private enum InjectorType {
    LOG4J("org.apache.log4j.MDC", "datadog.trace.correlation.Log4jCorrelationIdInjector"),
    LOG4J2(
        "org.apache.logging.log4j.ThreadContext",
        "datadog.trace.correlation.Log4j2CorrelationIdInjector"),
    SLF4J_AND_LOGBACK("org.slf4j.MDC", "datadog.trace.correlation.Slf4jCorrelationIdInjector");

    private final String mdcClassName;
    private final String injectorClassName;

    InjectorType(String mdcClassName, String injectorClassName) {
      this.mdcClassName = mdcClassName;
      this.injectorClassName = injectorClassName;
    }

    void register(InternalTracer tracer) {
      if (isClassPresent(this.mdcClassName)) {
        enableInjector(this.injectorClassName, tracer);
      }
    }
  }
}
