package com.datadog.debugger.exception;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.datadog.debugger.agent.ConfigurationAcceptor;
import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.util.ClassNameFiltering;
import org.junit.jupiter.api.Test;

class DefaultExceptionDebuggerTest {

  @Test
  public void test() {
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(emptyList());
    ConfigurationUpdater configurationUpdater = mock(ConfigurationUpdater.class);
    DefaultExceptionDebugger exceptionDebugger =
        new DefaultExceptionDebugger(configurationUpdater, classNameFiltering);
    RuntimeException exception = new RuntimeException("test");
    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    exceptionDebugger.handleException(exception);
    exceptionDebugger.handleException(exception);
    assertTrue(exceptionDebugger.getExceptionProbeManager().isAlreadyInstrumented(fingerprint));
    verify(configurationUpdater).accept(eq(ConfigurationAcceptor.Source.EXCEPTION), any());
  }
}
