package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.config.DebuggerConfig;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.civisibility.CiVisibilitySmokeTest;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class JUnitConsoleSmokeTest extends CiVisibilitySmokeTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(JUnitConsoleSmokeTest.class);

  private static final String TEST_SERVICE_NAME = "test-headless-service";

  private static final int PROCESS_TIMEOUT_SECS = 60;
  private static final String JUNIT_CONSOLE_JAR_PATH =
      System.getProperty("datadog.smoketest.junit.console.jar.path");
  private static final String JAVA_HOME = buildJavaHome();

  @TempDir Path projectHome;

  static final MockBackend mockBackend = new MockBackend();

  @BeforeEach
  void resetMockBackend() {
    mockBackend.reset();
  }

  @AfterAll
  static void closeMockBackend() throws Exception {
    mockBackend.close();
  }

  @Test
  void testHeadlessFailedTestReplay() throws Exception {
    String projectName = "test_junit_console_failed_test_replay";
    givenProjectFiles(projectName);

    mockBackend.givenFlakyRetries(true);
    mockBackend.givenFlakyTest("test-headless-service", "com.example.TestFailed", "test_failed");
    mockBackend.givenFailedTestReplay(true);

    int compileCode = compileTestProject();
    assertEquals(0, compileCode);

    Map<String, String> agentArgs = new HashMap<>();
    agentArgs.put(CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_COUNT, "3");
    agentArgs.put(GeneralConfig.AGENTLESS_LOG_SUBMISSION_URL, mockBackend.getIntakeUrl());
    // avoid possible race conditions on shutdown
    agentArgs.put(DebuggerConfig.DYNAMIC_INSTRUMENTATION_UPLOAD_FLUSH_INTERVAL, "999999");

    int exitCode = whenRunningJUnitConsole(agentArgs, Collections.emptyMap());
    assertEquals(1, exitCode);

    List<String> additionalDynamicTags =
        Arrays.asList(
            "content.meta.['_dd.debug.error.6.snapshot_id']",
            "content.meta.['_dd.debug.error.exception_id']");
    verifyEventsAndCoverages(
        projectName,
        "junit-console",
        "headless",
        mockBackend.waitForEvents(7),
        mockBackend.waitForCoverages(0),
        additionalDynamicTags);
    verifySnapshots(mockBackend.waitForLogs(2), 2);
  }

  private void givenProjectFiles(String projectFilesSources) throws Exception {
    Path projectResourcesPath =
        Paths.get(this.getClass().getClassLoader().getResource(projectFilesSources).toURI());
    copyFolder(projectResourcesPath, projectHome);
  }

  private void copyFolder(Path src, Path dest) throws IOException {
    Files.walkFileTree(
        src,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(dest.resolve(src.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(file, dest.resolve(src.relativize(file)));
            return FileVisitResult.CONTINUE;
          }
        });

    // creating empty .git directory so that the tracer could detect projectFolder as repo root
    Files.createDirectory(projectHome.resolve(".git"));
  }

  private int compileTestProject() throws Exception {
    Path srcDir = projectHome.resolve("src/main/java");
    Path testSrcDir = projectHome.resolve("src/test/java");
    Path classesDir = projectHome.resolve("target/classes");
    Path testClassesDir = projectHome.resolve("target/test-classes");

    Files.createDirectories(classesDir);
    Files.createDirectories(testClassesDir);

    // Compile main classes if they exist
    if (Files.exists(srcDir)) {
      List<String> mainJavaFiles = findJavaFiles(srcDir);
      if (!mainJavaFiles.isEmpty()) {
        int result =
            runProcess(
                createCompilerProcessBuilder(
                        classesDir.toString(), mainJavaFiles, Collections.emptyList())
                    .start(),
                PROCESS_TIMEOUT_SECS);
        if (result != 0) {
          LOGGER.error("Error compiling source classes for JUnit Console smoke test");
          return result;
        }
      }
    }

    // Compile test classes
    List<String> testJavaFiles = findJavaFiles(testSrcDir);
    if (!testJavaFiles.isEmpty()) {
      int result =
          runProcess(
              createCompilerProcessBuilder(
                      testClassesDir.toString(),
                      testJavaFiles,
                      Collections.singletonList(classesDir.toString()))
                  .start(),
              PROCESS_TIMEOUT_SECS);
      if (result != 0) {
        LOGGER.error("Error compiling source classes for JUnit Console smoke test");
        return result;
      }
    }

    return 0;
  }

  private ProcessBuilder createCompilerProcessBuilder(
      String targetDir, List<String> files, List<String> additionalDeps) {
    assertTrue(new File(JUNIT_CONSOLE_JAR_PATH).isFile());

    List<String> deps = new ArrayList<>();
    deps.add(JUNIT_CONSOLE_JAR_PATH);
    deps.addAll(additionalDeps);

    List<String> command = new ArrayList<>();
    command.add(javacPath());
    command.addAll(Arrays.asList("-cp", String.join(":", deps)));
    command.addAll(Arrays.asList("-d", targetDir));
    command.addAll(files);

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(projectHome.toFile());
    processBuilder.environment().put("JAVA_HOME", JAVA_HOME);
    return processBuilder;
  }

  private static List<String> findJavaFiles(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return Collections.emptyList();
    }

    List<String> javaFiles = new ArrayList<>();
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (file.toString().endsWith(".java")) {
              javaFiles.add(file.toString());
            }
            return FileVisitResult.CONTINUE;
          }
        });

    return javaFiles;
  }

  private int whenRunningJUnitConsole(
      Map<String, String> additionalAgentArgs, Map<String, String> additionalEnvVars)
      throws Exception {
    ProcessBuilder processBuilder =
        createConsoleProcessBuilder(
            Collections.singletonList("execute"), additionalAgentArgs, additionalEnvVars);
    processBuilder.environment().put("DD_API_KEY", "01234567890abcdef123456789ABCDEF");
    return runProcess(processBuilder.start(), PROCESS_TIMEOUT_SECS);
  }

  private static int runProcess(Process p, int timeoutSecs) throws Exception {
    StreamConsumer errorGobbler = new StreamConsumer(p.getErrorStream(), "ERROR");
    StreamConsumer outputGobbler = new StreamConsumer(p.getInputStream(), "OUTPUT");
    outputGobbler.start();
    errorGobbler.start();

    if (!p.waitFor(timeoutSecs, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new TimeoutException(
          "Instrumented process failed to exit within " + timeoutSecs + "  seconds");
    }

    return p.exitValue();
  }

  ProcessBuilder createConsoleProcessBuilder(
      List<String> consoleCommand,
      Map<String, String> additionalAgentArgs,
      Map<String, String> additionalEnvVars) {
    assertTrue(new File(JUNIT_CONSOLE_JAR_PATH).isFile());

    List<String> command = new ArrayList<>();
    command.add(javaPath());
    command.add("-Ddatadog.slf4j.simpleLogger.defaultLogLevel=DEBUG");
    command.addAll(Arrays.asList("-jar", JUNIT_CONSOLE_JAR_PATH));
    command.addAll(consoleCommand);
    command.addAll(
        Arrays.asList(
            "--class-path",
            projectHome.resolve("target/classes")
                + ":"
                + projectHome.resolve("target/test-classes")));
    command.add("--scan-class-path");

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(projectHome.toFile());

    processBuilder.environment().put("JAVA_HOME", JAVA_HOME);
    processBuilder.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions(additionalAgentArgs));
    for (Map.Entry<String, String> envVar : additionalEnvVars.entrySet()) {
      processBuilder.environment().put(envVar.getKey(), envVar.getValue());
    }

    String mavenRepositoryProxy = System.getenv("MAVEN_REPOSITORY_PROXY");
    if (mavenRepositoryProxy != null) {
      processBuilder.environment().put("MAVEN_REPOSITORY_PROXY", mavenRepositoryProxy);
    }

    return processBuilder;
  }

  String javaToolOptions(Map<String, String> additionalAgentArgs) {
    additionalAgentArgs.put(CiVisibilityConfig.CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED, "false");
    return buildJvmArguments(mockBackend.getIntakeUrl(), TEST_SERVICE_NAME, additionalAgentArgs)
        .stream()
        .collect(Collectors.joining(" "));
  }

  private static class StreamConsumer extends Thread {
    final InputStream is;
    final String messagePrefix;

    StreamConsumer(InputStream is, String messagePrefix) {
      this.is = is;
      this.messagePrefix = messagePrefix;
    }

    @Override
    public void run() {
      try {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = br.readLine()) != null) {
          System.out.println(messagePrefix + ": " + line);
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
}
