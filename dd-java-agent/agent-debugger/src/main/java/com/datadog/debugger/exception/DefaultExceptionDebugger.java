package com.datadog.debugger.exception;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.util.CircuitBreaker;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default implementation of {@link DebuggerContext.ExceptionDebugger} for Exception Replay. */
public class DefaultExceptionDebugger extends AbstractExceptionDebugger {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultExceptionDebugger.class);

  private final CircuitBreaker circuitBreaker;

  public DefaultExceptionDebugger(
      ConfigurationUpdater configurationUpdater,
      ClassNameFilter classNameFiltering,
      Config config) {
    this(
        new ExceptionProbeManager(
            classNameFiltering, Duration.ofSeconds(config.getDebuggerExceptionCaptureInterval())),
        configurationUpdater,
        classNameFiltering,
        config.getDebuggerMaxExceptionPerSecond(),
        config.getDebuggerExceptionMaxCapturedFrames(),
        true);
  }

  DefaultExceptionDebugger(
      ExceptionProbeManager exceptionProbeManager,
      ConfigurationUpdater configurationUpdater,
      DebuggerContext.ClassNameFilter classNameFiltering,
      int maxExceptionPerSecond,
      int maxCapturedFrames,
      boolean applyConfigAsync) {
    super(
        exceptionProbeManager,
        configurationUpdater,
        classNameFiltering,
        maxCapturedFrames,
        applyConfigAsync);

    this.circuitBreaker = new CircuitBreaker(maxExceptionPerSecond, Duration.ofSeconds(1));
  }

  @Override
  protected boolean shouldHandleException(Throwable t, AgentSpan span) {
    if (t instanceof Error) {
      return false;
    }
    return circuitBreaker.trip();
  }
}
