package com.datadog.profiling.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.ControllerContext;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Note: some additional tests for this class are located in profiling-controller-openjdk module */
public class CompositeControllerTest {

  @Test
  public void smokeTest() {
    UnsupportedEnvironmentException unsupportedEnvironmentException = null;
    try {
      CompositeController.build(ConfigProvider.getInstance(), new ControllerContext());
      // successfully created controller, return
      return;
    } catch (UnsupportedEnvironmentException e) {
      unsupportedEnvironmentException = e;
    }
    final String javaVendor = System.getProperty("java.vendor");
    final String javaRuntimeName = System.getProperty("java.runtime.name");
    final String javaVersion = System.getProperty("java.version");
    String expected =
        "Not enabling profiling for vendor="
            + javaVendor
            + ", version="
            + javaVersion
            + ", runtimeName="
            + javaRuntimeName
            + "; it requires ";
    if (javaVendor.equals("Azul Systems, Inc.")) {
      expected += "Zulu Java 8 (1.8.0_212+).";
    } else if (javaVendor.equals("Oracle Corporation") && !javaRuntimeName.startsWith("OpenJDK")) {
      // condition for Oracle JDK 8 (with proprietary JFR inside)
      expected += "Oracle JRE/JDK 8u40+";
    } else if (javaVendor.equals("Oracle Corporation") && javaRuntimeName.startsWith("OpenJDK")) {
      // condition for Oracle OpenJDK 8 (with open JFR inside)
      expected += "1.8.0_272+ OpenJDK builds (upstream)";
    } else if (javaRuntimeName.startsWith("OpenJDK")) {
      expected +=
          "1.8.0_272+ OpenJDK builds from the following vendors: AdoptOpenJDK, Eclipse Temurin, Amazon Corretto, Azul Zulu, BellSoft Liberica.";
    } else {
      expected += "OpenJDK 11+, Oracle Java 11+, or Zulu Java 8 (1.8.0_212+).";
    }
    assertEquals(
        expected,
        unsupportedEnvironmentException.getMessage(),
        "'" + javaRuntimeName + "' / '" + javaVendor + "' / '" + javaVersion + "'");
  }

  @Test
  public void stopStopsEveryRecordingEvenWhenOneFails() throws Exception {
    OngoingRecording failing = mock(OngoingRecording.class);
    OngoingRecording other = mock(OngoingRecording.class);
    IllegalStateException failure = new IllegalStateException("not running");
    when(failing.stop()).thenThrow(failure);
    when(other.stop()).thenReturn(mock(RecordingData.class));
    OngoingRecording composite = composite(failing, other);

    // the recording after the failing one must still be stopped, or it would keep running
    assertSame(failure, assertThrows(IllegalStateException.class, composite::stop));

    verify(other).stop();
  }

  @Test
  public void stopReleasesTheDataOfTheRecordingsThatStoppedWhenAnotherFails() throws Exception {
    RecordingData stopped = mock(RecordingData.class);
    OngoingRecording ok = mock(OngoingRecording.class);
    OngoingRecording failing = mock(OngoingRecording.class);
    when(ok.stop()).thenReturn(stopped);
    when(failing.stop()).thenThrow(new IllegalStateException("not running"));

    assertThrows(IllegalStateException.class, composite(ok, failing)::stop);

    verify(stopped).release();
  }

  @Test
  public void closeClosesEveryRecordingEvenWhenSomeFail() throws Exception {
    OngoingRecording failing = mock(OngoingRecording.class);
    OngoingRecording alsoFailing = mock(OngoingRecording.class);
    OngoingRecording other = mock(OngoingRecording.class);
    IllegalStateException failure = new IllegalStateException("first");
    IllegalStateException otherFailure = new IllegalStateException("second");
    doThrow(failure).when(failing).close();
    doThrow(otherFailure).when(alsoFailing).close();
    OngoingRecording composite = composite(failing, alsoFailing, other);

    // the recordings after a failing one must still be closed, or they would keep running
    assertSame(failure, assertThrows(IllegalStateException.class, composite::close));

    verify(other).close();
    assertArrayEquals(new Throwable[] {otherFailure}, failure.getSuppressed());
  }

  @Test
  public void createRecordingClosesTheStartedRecordingsWhenALaterControllerFails()
      throws Exception {
    OngoingRecording jfr = mock(OngoingRecording.class);
    Controller jfrController = mock(Controller.class);
    when(jfrController.createRecording(any(), any())).thenReturn(jfr);
    Controller ddprofController = mock(Controller.class);
    IllegalStateException failure = new IllegalStateException("ddprof failed to start");
    when(ddprofController.createRecording(any(), any())).thenThrow(failure);
    Controller notReached = mock(Controller.class);
    CompositeController controller =
        new CompositeController(Arrays.asList(jfrController, ddprofController, notReached));

    // the caller gets no recording to stop, so the started JFR recording must not be left running
    assertSame(
        failure,
        assertThrows(IllegalStateException.class, () -> controller.createRecording("test", null)));

    verify(jfr).close();
    verify(notReached, never()).createRecording(any(), any());
  }

  @Test
  public void createRecordingKeepsTheFailureWhenClosingAStartedRecordingFails() throws Exception {
    OngoingRecording started = mock(OngoingRecording.class);
    IllegalStateException closeFailure = new IllegalStateException("close failed");
    doThrow(closeFailure).when(started).close();
    Controller startedController = mock(Controller.class);
    when(startedController.createRecording(any(), any())).thenReturn(started);
    Controller failingController = mock(Controller.class);
    UnsupportedEnvironmentException failure = new UnsupportedEnvironmentException("unsupported");
    when(failingController.createRecording(any(), any())).thenThrow(failure);
    CompositeController controller =
        new CompositeController(Arrays.asList(startedController, failingController));

    assertSame(
        failure,
        assertThrows(
            UnsupportedEnvironmentException.class, () -> controller.createRecording("test", null)));

    assertArrayEquals(new Throwable[] {closeFailure}, failure.getSuppressed());
  }

  private static OngoingRecording composite(OngoingRecording... recordings) throws Exception {
    Controller[] controllers = new Controller[recordings.length];
    for (int i = 0; i < recordings.length; i++) {
      controllers[i] = mock(Controller.class);
      when(controllers[i].createRecording(any(), any())).thenReturn(recordings[i]);
    }
    return new CompositeController(Arrays.asList(controllers)).createRecording("test", null);
  }
}
