package datadog.smoketest;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ProcessBuilderHelper {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessBuilderHelper.class);

  public static ProcessBuilder createProcessBuilder(
      List<String> additionalCommandParams,
      Path logFilePath,
      String mainClassName,
      String... params) {
    return createProcessBuilder(
        appTestShadowJar(), additionalCommandParams, logFilePath, mainClassName, params);
  }

  public static ProcessBuilder createProcessBuilder(
      String classpath,
      List<String> additionalCommandParams,
      Path logFilePath,
      String mainClassName,
      String... params) {

    // Trick to prevent jul preferences file lock issue on forked processes, in particular in CI
    // which runs on Linux and have competing processes trying to write to it, including the
    // Gradle daemon.
    //
    //   Couldn't flush user prefs: java.util.prefs.BackingStoreException: Couldn't get file lock.
    String prefsDir =
        System.getProperty("java.io.tmpdir")
            + File.separator
            + "userPrefs"
            + File.separator
            + mainClassName
            + "_"
            + System.nanoTime();

    List<String> baseCommand =
        Arrays.asList(
            javaPath(),
            // "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:5006",
            "-Xmx" + System.getProperty("datadog.forkedMaxHeapSize", "1024M"),
            "-Xms" + System.getProperty("datadog.forkedMinHeapSize", "64M"),
            "-javaagent:" + agentShadowJar(),
            "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
            "-Ddd.env=smoketest",
            "-Ddd.version=99",
            "-Djava.util.prefs.userRoot=" + prefsDir);

    List<String> command = new ArrayList<>();
    command.addAll(baseCommand);
    command.addAll(additionalCommandParams);
    command.addAll(Arrays.asList("-cp", classpath, mainClassName));
    command.addAll(Arrays.asList(params));

    LOG.info("Launching: {}", command);
    LOG.info("LogFilePath: {}", logFilePath);
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(new File(buildDirectory()));

    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    processBuilder.environment().put("DD_LOG_FILE", logFilePath.toString());

    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFilePath.toFile()));
    return processBuilder;
  }

  private static String javaPath() {
    final String separator = System.getProperty("file.separator");
    return System.getProperty("java.home") + separator + "bin" + separator + "java";
  }

  private static String agentShadowJar() {
    return System.getProperty("datadog.smoketest.agent.shadowJar.path");
  }

  private static String appTestShadowJar() {
    return System.getProperty("datadog.smoketest.shadowJar.path");
  }

  static String buildDirectory() {
    return System.getProperty("datadog.smoketest.builddir");
  }
}
