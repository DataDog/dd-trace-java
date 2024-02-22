package com.datadog.profiling.agent;

import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.ControllerContext;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.controller.ddprof.DatadogProfilerController;
import com.datadog.profiling.controller.openjdk.OpenJdkController;
import com.datadog.profiling.controller.oracle.OracleJdkController;
import com.datadog.profiling.ddprof.Arch;
import com.datadog.profiling.ddprof.OperatingSystem;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Composer {

  private static final Logger log = LoggerFactory.getLogger(Composer.class);

  /**
   * Selects which profiler implementations are relevant for the given environment.
   *
   * @param provider
   * @return a controller
   */
  @SuppressForbidden
  public static Controller compose(ConfigProvider provider, ControllerContext context)
      throws UnsupportedEnvironmentException {
    List<Controller> controllers = new ArrayList<>();
    boolean isOracleJDK8 = Platform.isOracleJDK8();
    boolean isDatadogProfilerEnabled = Config.get().isDatadogProfilerEnabled();
    if (ConfigProvider.getInstance()
        .getBoolean(ProfilingConfig.PROFILING_DEBUG_JFR_DISABLED, false)) {
      log.warn("JFR is disabled by configuration");
    } else {
      if (isOracleJDK8 && !isDatadogProfilerEnabled) {
        try {
          Class.forName("com.oracle.jrockit.jfr.Producer");
          controllers.add(OracleJdkController.instance(provider));
        } catch (Throwable ignored) {
          log.debug("Failed to load oracle profiler", ignored);
        }
      }
      if (!isOracleJDK8) {
        try {
          Class.forName("jdk.jfr.Event");
          controllers.add(OpenJdkController.instance(provider));
        } catch (Throwable ignored) {
          log.debug("Failed to load openjdk profiler", ignored);
        }
      }
    }
    // Datadog profiler is not supported in native-image mode or on Windows, so don't try to
    // instantiate it
    if ((Platform.isLinux() || Platform.isMac())
        && isDatadogProfilerEnabled
        && !(Platform.isNativeImageBuilder() || Platform.isNativeImage())) {
      try {
        controllers.add(DatadogProfilerController.instance(provider));
      } catch (Throwable error) {
        OperatingSystem os = OperatingSystem.current();
        if (os != OperatingSystem.linux) {
          log.debug("Datadog profiler only supported on Linux", error);
        } else if (log.isDebugEnabled()) {
          log.warn(
              String.format("failed to instantiate Datadog profiler on %s %s", os, Arch.current()),
              error);
        } else {
          log.warn(
              "failed to instantiate Datadog profiler on {} {} because: {}",
              os,
              Arch.current(),
              error.getMessage());
        }
      }
    }
    controllers.forEach(controller -> controller.configure(context));
    if (controllers.isEmpty()) {
      throw new UnsupportedEnvironmentException(getFixProposalMessage());
    } else if (controllers.size() == 1) {
      return controllers.get(0);
    }
    return new CompositeController(controllers);
  }

  private static String getFixProposalMessage() {
    final String javaVendor = System.getProperty("java.vendor");
    final String javaVersion = System.getProperty("java.version");
    final String javaRuntimeName = System.getProperty("java.runtime.name");
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
