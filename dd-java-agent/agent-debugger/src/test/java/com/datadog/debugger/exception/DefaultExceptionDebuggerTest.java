package com.datadog.debugger.exception;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.datadog.debugger.agent.ConfigurationUpdater;
import org.junit.jupiter.api.Test;

class DefaultExceptionDebuggerTest {

  @Test
  public void test() {
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager();
    ConfigurationUpdater configurationUpdater = mock(ConfigurationUpdater.class);
    DefaultExceptionDebugger exceptionDebugger =
        new DefaultExceptionDebugger(exceptionProbeManager, configurationUpdater);
    RuntimeException exception = new RuntimeException("test");
    String fingerprint = Fingerprinter.fingerprint(exception);
    exceptionDebugger.handleException(exception);
    exceptionDebugger.handleException(exception);
    assertTrue(exceptionProbeManager.isAlreadyInstrumented(fingerprint));
  }
}
