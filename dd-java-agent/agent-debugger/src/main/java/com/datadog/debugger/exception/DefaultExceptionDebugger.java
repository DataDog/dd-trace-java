package com.datadog.debugger.exception;

import static com.datadog.debugger.agent.DebuggerProductChangesListener.ConfigurationAcceptor.Source.EXCEPTION;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link DebuggerContext.ExceptionDebugger} that uses {@link
 * ExceptionProbeManager} to instrument the exception stacktrace and send snapshots.
 */
public class DefaultExceptionDebugger implements DebuggerContext.ExceptionDebugger {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultExceptionDebugger.class);
  private final ExceptionProbeManager exceptionProbeManager;
  private final ConfigurationUpdater configurationUpdater;
  private final ClassNameFiltering classNameFiltering;

  public DefaultExceptionDebugger(
      ConfigurationUpdater configurationUpdater, ClassNameFiltering classNameFiltering) {
    this(new ExceptionProbeManager(classNameFiltering), configurationUpdater, classNameFiltering);
  }

  DefaultExceptionDebugger(
      ExceptionProbeManager exceptionProbeManager,
      ConfigurationUpdater configurationUpdater,
      ClassNameFiltering classNameFiltering) {
    this.exceptionProbeManager = exceptionProbeManager;
    this.configurationUpdater = configurationUpdater;
    this.classNameFiltering = classNameFiltering;
  }

  @Override
  public void handleException(Throwable t) {
    String fingerprint = Fingerprinter.fingerprint(t, classNameFiltering);
    if (fingerprint == null) {
      LOGGER.debug("Unable to fingerprint exception", t);
      return;
    }
    if (exceptionProbeManager.isAlreadyInstrumented(fingerprint)) {
      // TODO trigger send snapshots already captured
    } else {
      exceptionProbeManager.createProbesForException(fingerprint, t.getStackTrace());
      // TODO make it async
      configurationUpdater.accept(EXCEPTION, exceptionProbeManager.getProbes());
    }
  }

  ExceptionProbeManager getExceptionProbeManager() {
    return exceptionProbeManager;
  }
}
