package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;

import com.datadog.debugger.agent.ProbeStatus.Builder;
import com.datadog.debugger.agent.ProbeStatus.Diagnostics;
import com.datadog.debugger.agent.ProbeStatus.ProbeException;
import com.datadog.debugger.agent.ProbeStatus.Status;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProbeStatusTest {

  private static final String SERVICE_NAME = "service-name";
  private static final String PROBE_ID = "probe-id";
  private static final String RECEIVED_MESSAGE = "Received probe " + PROBE_ID + ".";
  private static final String INSTALLED_MESSAGE = "Installed probe " + PROBE_ID + ".";
  private static final String ERROR_MESSAGE = "Error installing probe " + PROBE_ID + ".";

  @Mock private Config config;

  private Builder builder;

  @BeforeEach
  void setUp() {
    lenient().when(config.getServiceName()).thenReturn(SERVICE_NAME);
    builder = new Builder(config);
  }

  @Test
  void builderReceived() {
    ProbeStatus expected =
        new ProbeStatus(
            SERVICE_NAME, RECEIVED_MESSAGE, new Diagnostics(PROBE_ID, Status.RECEIVED, null));
    ProbeStatus actual = builder.receivedMessage(PROBE_ID);
    assertEquals(expected, actual);
  }

  @Test
  void builderInstalled() {
    ProbeStatus expected =
        new ProbeStatus(
            SERVICE_NAME, INSTALLED_MESSAGE, new Diagnostics(PROBE_ID, Status.INSTALLED, null));
    ProbeStatus actual = builder.installedMessage(PROBE_ID);
    assertEquals(expected, actual);
  }

  @Test
  void builderErrorMessage() {
    String exceptionMessage = "foo";
    ProbeStatus expected =
        new ProbeStatus(
            SERVICE_NAME,
            ERROR_MESSAGE,
            new Diagnostics(
                PROBE_ID,
                Status.ERROR,
                new ProbeException("NO_TYPE", exceptionMessage, Collections.emptyList())));
    ProbeStatus actual = builder.errorMessage(PROBE_ID, exceptionMessage);
    assertEquals(expected, actual);
  }

  @Test
  void builderErrorThrowable() {
    String exceptionMessage = "foo";
    Exception exception = new Exception(exceptionMessage);
    List<CapturedStackFrame> capturedStackFrames =
        Arrays.stream(exception.getStackTrace())
            .map(CapturedStackFrame::from)
            .collect(Collectors.toList());
    ProbeStatus expected =
        new ProbeStatus(
            SERVICE_NAME,
            ERROR_MESSAGE,
            new Diagnostics(
                PROBE_ID,
                Status.ERROR,
                new ProbeException("java.lang.Exception", exceptionMessage, capturedStackFrames)));
    ProbeStatus actual = builder.errorMessage(PROBE_ID, exception);
    assertEquals(expected, actual);
  }

  @Test
  void received() {
    Diagnostics diagnostics = new Diagnostics(PROBE_ID, Status.RECEIVED, null);
    ProbeStatus message = new ProbeStatus(SERVICE_NAME, RECEIVED_MESSAGE, diagnostics);
    assertEquals(SERVICE_NAME, message.getService());
    assertEquals(RECEIVED_MESSAGE, message.getMessage());
    assertEquals(diagnostics, message.getDiagnostics());
  }

  @Test
  void installed() {
    Diagnostics diagnostics = new Diagnostics(PROBE_ID, Status.INSTALLED, null);
    ProbeStatus message = new ProbeStatus(SERVICE_NAME, INSTALLED_MESSAGE, diagnostics);
    assertEquals(SERVICE_NAME, message.getService());
    assertEquals(INSTALLED_MESSAGE, message.getMessage());
    assertEquals(diagnostics, message.getDiagnostics());
  }

  @Test
  void errorMessage() {
    ProbeException exception =
        new ProbeException("NO_TYPE", ERROR_MESSAGE, Collections.emptyList());
    Diagnostics diagnostics = new Diagnostics(PROBE_ID, Status.ERROR, exception);
    ProbeStatus message = new ProbeStatus(SERVICE_NAME, ERROR_MESSAGE, diagnostics);
    assertEquals(SERVICE_NAME, message.getService());
    assertEquals(ERROR_MESSAGE, message.getMessage());
    assertEquals(diagnostics, message.getDiagnostics());
    assertEquals(exception, message.getDiagnostics().getException());
  }

  @Test
  void errorException() {
    List<CapturedStackFrame> stackTrace =
        Arrays.stream(Thread.currentThread().getStackTrace())
            .map(CapturedStackFrame::from)
            .collect(Collectors.toList());
    ProbeException probeException =
        new ProbeException("java.lang.Exception", ERROR_MESSAGE, stackTrace);
    Diagnostics diagnostics = new Diagnostics(PROBE_ID, Status.ERROR, probeException);
    ProbeStatus message = new ProbeStatus(SERVICE_NAME, ERROR_MESSAGE, diagnostics);
    assertEquals(SERVICE_NAME, message.getService());
    assertEquals(ERROR_MESSAGE, message.getMessage());
    assertEquals(diagnostics, message.getDiagnostics());
    assertEquals(probeException, message.getDiagnostics().getException());
  }
}
