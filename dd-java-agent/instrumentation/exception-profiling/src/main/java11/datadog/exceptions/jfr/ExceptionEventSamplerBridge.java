package datadog.exceptions.jfr;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.jfr.exceptions.ExceptionEventSampler;
import datadog.trace.bootstrap.instrumentation.jfr.exceptions.ExceptionSampleEvent;

/**
 * A simple accessor bridge implementation.
 * {@linkplain datadog.exceptions.instrumentation.ExceptionAdvice} can only access classes declared by {@linkplain Instrumenter.Default#helperClassNames()}
 * but these helper classes are loaded per the instrumented class classloader - hence it may be loaded multiple times.
 * However, the {@linkplain ExceptionEventSampler} instance must be a singleton within the context of JVM. Therefore it is
 * not possible to use that class directly as a helper and it needs to be accessed via this bridge.
 */
public class ExceptionEventSamplerBridge {
  public static ExceptionSampleEvent sample(Exception e) {
    return ExceptionEventSampler.sample(e);
  }
}
