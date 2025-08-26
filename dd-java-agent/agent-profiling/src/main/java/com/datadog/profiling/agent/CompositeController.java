package com.datadog.profiling.agent;

import static datadog.environment.OperatingSystem.isLinux;
import static datadog.environment.OperatingSystem.isMacOs;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.ControllerContext;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.controller.ddprof.DatadogProfilerController;
import com.datadog.profiling.controller.openjdk.OpenJdkController;
import com.datadog.profiling.controller.oracle.OracleJdkController;
import com.datadog.profiling.ddprof.Arch;
import com.datadog.profiling.ddprof.OperatingSystem;
import datadog.config.ConfigProvider;
import datadog.environment.JavaVirtualMachine;
import datadog.environment.SystemProperties;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingInputStream;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class composes one or more controllers (i.e. profilers), synchronizing their recordings and
 * concatenating their outputs.
 */
public class CompositeController implements Controller {

  private static final Logger log = LoggerFactory.getLogger(CompositeController.class);

  private final List<Controller> controllers;

  public CompositeController(List<Controller> controllers) {
    assert !controllers.isEmpty() : "no controllers created";
    this.controllers = controllers;
  }

  // visible for testing
  public List<Controller> getControllers() {
    return Collections.unmodifiableList(controllers);
  }

  @Nonnull
  @Override
  public OngoingRecording createRecording(
      @Nonnull String recordingName, ControllerContext.Snapshot context)
      throws UnsupportedEnvironmentException {
    List<OngoingRecording> recordings = new ArrayList<>(controllers.size());
    for (Controller controller : controllers) {
      recordings.add(controller.createRecording(recordingName, context));
    }
    return new CompositeOngoingRecording(recordings);
  }

  private static class CompositeOngoingRecording implements OngoingRecording {

    private final List<OngoingRecording> recordings;

    private CompositeOngoingRecording(List<OngoingRecording> recordings) {
      this.recordings = recordings;
    }

    @Nonnull
    @Override
    public RecordingData stop() {
      return compose(OngoingRecording::stop);
    }

    @Nonnull
    @Override
    public RecordingData snapshot(@Nonnull Instant start, ProfilingSnapshot.Kind kind) {
      return compose(recording -> recording.snapshot(start, kind));
    }

    @Override
    public void close() {
      recordings.forEach(OngoingRecording::close);
    }

    private RecordingData compose(Function<OngoingRecording, RecordingData> recorder) {
      return new CompositeRecordingData(
          recordings.stream().map(recorder).collect(Collectors.toList()));
    }
  }

  private static class CompositeRecordingData extends RecordingData {
    private final List<RecordingData> data;

    public CompositeRecordingData(List<RecordingData> data) {
      super(first(data).getStart(), first(data).getEnd(), first(data).getKind());
      this.data = data;
    }

    @Nonnull
    @Override
    public RecordingInputStream getStream() throws IOException {
      List<RecordingInputStream> streams = new ArrayList<>(data.size());
      for (RecordingData item : data) {
        streams.add(item.getStream());
      }
      return new RecordingInputStream(new SequenceInputStream(Collections.enumeration(streams)));
    }

    @Override
    public void release() {
      for (RecordingData data : data) {
        data.release();
      }
    }

    @Nonnull
    @Override
    public String getName() {
      return first(data).getName();
    }

    private static <T> T first(List<T> list) {
      return list.get(0);
    }
  }

  /**
   * Selects which profiler implementations are relevant for the given environment.
   *
   * @param provider
   * @return a controller
   */
  @SuppressForbidden
  public static Controller build(ConfigProvider provider, ControllerContext context)
      throws UnsupportedEnvironmentException {
    List<Controller> controllers = new ArrayList<>();
    boolean isOracleJDK8 = JavaVirtualMachine.isOracleJDK8();
    boolean isDatadogProfilerEnabled = Config.get().isDatadogProfilerEnabled();
    boolean isJfrEnabled =
        !provider.getBoolean(ProfilingConfig.PROFILING_DEBUG_JFR_DISABLED, false);
    if (!isJfrEnabled) {
      log.warn(SEND_TELEMETRY, "JFR is disabled by configuration");
    } else {
      if (isOracleJDK8 && !isDatadogProfilerEnabled) {
        try {
          Class.forName("com.oracle.jrockit.jfr.Producer");
          controllers.add(OracleJdkController.instance(provider));
        } catch (Throwable t) {
          log.debug(SEND_TELEMETRY, "Failed to load oracle profiler: " + t.getMessage(), t);
        }
      }
      if (!isOracleJDK8) {
        try {
          if (Platform.hasJfr()) {
            controllers.add(OpenJdkController.instance(provider));
          } else {
            log.debug(
                SEND_TELEMETRY,
                "JFR is not available on this platform: "
                    + OperatingSystem.current()
                    + ", "
                    + Arch.current());
          }
        } catch (Throwable t) {
          log.debug(SEND_TELEMETRY, "Failed to load openjdk profiler: " + t.getMessage(), t);
        }
      }
    }
    // Datadog profiler is not supported in native-image mode or on Windows, so don't try to
    // instantiate it
    if ((isLinux() || isMacOs())
        && isDatadogProfilerEnabled
        && !(Platform.isNativeImageBuilder() || Platform.isNativeImage())) {
      try {
        controllers.add(DatadogProfilerController.instance(provider));
      } catch (Throwable error) {
        Throwable rootCause = error.getCause() == null ? error : error.getCause();
        context.setDatadogProfilerUnavailableReason(rootCause.getMessage());
        OperatingSystem os = OperatingSystem.current();
        if (os != OperatingSystem.linux) {
          log.debug(SEND_TELEMETRY, "Datadog profiler only supported on Linux", rootCause);
        } else if (!log.isDebugEnabled()) {
          log.warn(
              "failed to instantiate Datadog profiler on {} {} because: {}",
              os,
              Arch.current(),
              rootCause.getMessage());
        } else {
          log.debug(
              SEND_TELEMETRY,
              "failed to instantiate Datadog profiler on {} {}",
              os,
              Arch.current(),
              rootCause);
        }
      }
    } else {
      if (!(isLinux() || isMacOs())) {
        context.setDatadogProfilerUnavailableReason("unavailable on OS");
      } else if (Platform.isNativeImageBuilder() || Platform.isNativeImage()) {
        context.setDatadogProfilerUnavailableReason("unavailable on GraalVM");
      } else if (!isDatadogProfilerEnabled) {
        if (!Config.isDatadogProfilerSafeInCurrentEnvironment()) {
          context.setDatadogProfilerUnavailableReason("not safe in current environment");
        } else {
          context.setDatadogProfilerUnavailableReason("disabled");
        }
      }
    }
    controllers.forEach(controller -> controller.configure(context));
    if (controllers.isEmpty()) {
      throw new UnsupportedEnvironmentException(
          getFixProposalMessage(isDatadogProfilerEnabled, isJfrEnabled));
    } else if (controllers.size() == 1) {
      return controllers.get(0);
    }
    return new CompositeController(controllers);
  }

  private static String getFixProposalMessage(boolean datadogProfilerEnabled, boolean jfrEnabled) {
    if (!datadogProfilerEnabled && !jfrEnabled) {
      return "Profiling is disabled by configuration. Please, make sure that your configuration is correct.";
    }
    final String javaVendor = SystemProperties.getOrDefault("java.vendor", "unknown");
    final String javaVersion = SystemProperties.getOrDefault("java.version", "unknown");
    final String javaRuntimeName = SystemProperties.getOrDefault("java.runtime.name", "unknown");
    final String message =
        "Not enabling profiling for vendor="
            + javaVendor
            + ", version="
            + javaVersion
            + ", runtimeName="
            + javaRuntimeName;
    try {
      if (javaVersion == null) {
        return message;
      }
      if (javaVersion.startsWith("1.8")) {
        if (javaVendor.startsWith("Azul Systems")) {
          return message + "; it requires Zulu Java 8 (1.8.0_212+).";
        }
        if (javaVendor.startsWith("Oracle")) {
          if (javaRuntimeName.startsWith("OpenJDK")) {
            // this is a upstream build from openjdk docker repository for example
            return message + "; it requires 1.8.0_272+ OpenJDK builds (upstream)";
          } else {
            // this is a proprietary Oracle JRE/JDK 8
            return message + "; it requires Oracle JRE/JDK 8u40+";
          }
        }
        if (javaRuntimeName.startsWith("OpenJDK")) {
          return message
              + "; it requires 1.8.0_272+ OpenJDK builds from the following vendors: AdoptOpenJDK, Eclipse Temurin, Amazon Corretto, Azul Zulu, BellSoft Liberica.";
        }
      }
      return message + "; it requires OpenJDK 11+, Oracle Java 11+, or Zulu Java 8 (1.8.0_212+).";
    } catch (final Exception ex) {
      return message;
    }
  }
}
