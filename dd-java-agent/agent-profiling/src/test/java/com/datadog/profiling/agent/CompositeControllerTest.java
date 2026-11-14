package com.datadog.profiling.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

  private static OngoingRecording composite(OngoingRecording... recordings) throws Exception {
    Controller[] controllers = new Controller[recordings.length];
    for (int i = 0; i < recordings.length; i++) {
      controllers[i] = mock(Controller.class);
      when(controllers[i].createRecording(any(), any())).thenReturn(recordings[i]);
    }
    return new CompositeController(Arrays.asList(controllers)).createRecording("test", null);
  }
}
