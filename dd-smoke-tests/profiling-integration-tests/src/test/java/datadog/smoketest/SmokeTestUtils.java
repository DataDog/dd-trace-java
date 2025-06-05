package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.config.ProfilingConfig;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class SmokeTestUtils {
  static ProcessBuilder createProcessBuilder(
      String targetClass,
      final int profilingStartDelaySecs,
      final int profilingUploadPeriodSecs,
      int cpuSamplerIntervalMs,
      int wallSamplerIntervalMs,
      final Path dumpPath,
      final Path logFilePath,
      String... args) {
    final String templateOverride =
        SmokeTestUtils.class.getClassLoader().getResource("overrides.jfp").getFile();

    final List<String> command =
        new ArrayList<>(
            Arrays.asList(
                javaPath(),
                "-Xmx" + System.getProperty("datadog.forkedMaxHeapSize", "1024M"),
                "-Xms" + System.getProperty("datadog.forkedMinHeapSize", "64M"),
                "-javaagent:" + agentShadowJar(),
                "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
                "-Ddd.service.name=smoke-test-code_hotspots-java-app",
                "-Ddd.env=smoketest",
                "-Ddd.version=99",
                "-Ddd.profiling.enabled=true",
                "-Ddd.profiling.ddprof.enabled=true",
                "-Ddd." + ProfilingConfig.PROFILING_AUXILIARY_TYPE + "=async",
                "-Ddd."
                    + ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_INTERVAL
                    + "="
                    + cpuSamplerIntervalMs
                    + "ms",
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED + "=true",
                "-Ddd."
                    + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_INTERVAL
                    + "="
                    + wallSamplerIntervalMs
                    + "ms",
                "-Ddd.profiling.agentless=false",
                "-Ddd.profiling.start-delay=" + profilingStartDelaySecs,
                "-Ddd." + ProfilingConfig.PROFILING_START_FORCE_FIRST + "=true",
                "-Ddd.profiling.ddprof.alloc.enabled=true",
                "-Ddd.profiling.upload.period=" + profilingUploadPeriodSecs,
                "-Ddd.profiling.hotspots.enabled=true",
                "-Ddd.profiling.legacy.tracing.integration=false",
                "-Ddd.profiling.endpoint.collection.enabled=true",
                "-Ddd.profiling.debug.dump_path=" + dumpPath,
                "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+UnlockCommercialFeatures",
                "-XX:+FlightRecorder",
                "-Ddd."
                    + ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE
                    + "="
                    + templateOverride));
    if (System.getenv("TEST_LIBASYNC") != null) {
      command.add(
          "-Ddd."
              + ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH
              + "="
              + System.getenv("TEST_LIBASYNC"));
    }
    command.addAll(Arrays.asList("-cp", profilingShadowJar(), targetClass));
    command.addAll(Arrays.asList(args));
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(new File(buildDirectory()));

    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));

    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFilePath.toFile()));
    return processBuilder;
  }

  static String javaPath() {
    final String separator = System.getProperty("file.separator");
    return System.getProperty("java.home") + separator + "bin" + separator + "java";
  }

  static String profilingShadowJar() {
    return System.getProperty("datadog.smoketest.profiling.shadowJar.path");
  }

  static String agentShadowJar() {
    return System.getProperty("datadog.smoketest.agent.shadowJar.path");
  }

  static String buildDirectory() {
    return System.getProperty("datadog.smoketest.builddir");
  }

  static void checkProcessSuccessfullyEnd(final Process process, final Path log)
      throws InterruptedException {
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      try {
        System.out.println(
            "=== Profiling application log start ==="
                + String.join("\n", Files.readAllLines(log))
                + "=== Profiling application log end ===");
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    assertEquals(0, exitCode, "Failed to run profiling process, exited in error");
  }
}
