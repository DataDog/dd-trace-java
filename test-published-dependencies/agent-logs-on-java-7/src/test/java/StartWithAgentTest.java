import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class StartWithAgentTest {

  private static final Pattern WARNING_PATTERN =
      Pattern.compile(
          "^Warning: Version [^ ]+ of dd-java-agent is not compatible with Java [^ ]+ found at [^ ]+ and is effectively disabled\\.$");
  private static final String UPGRADE_MESSAGE = "Please upgrade your Java version to 8+";

  @Test
  void ensureThatApplicationStartsWithAgentOnJava7() throws InterruptedException, IOException {
    String expectedMessage = "Woho! Started on Java 7";
    Process process = startAndWaitForJvmWithAgentForJava("JAVA_7_HOME", expectedMessage);
    int exitCode = process.waitFor();
    List<String> output = getLines(process.getInputStream());
    List<String> errors = getLines(process.getErrorStream());
    logProcessOutput(output, errors);
    assertEquals(0, exitCode, "Command failed with unexpected exit code");
    assertTrue(
        output.contains(expectedMessage), "Output does not contain '" + expectedMessage + "'");
    assertTrue(
        errors.stream().anyMatch(WARNING_PATTERN.asPredicate()),
        "Output does not contain line matching '" + WARNING_PATTERN + "'");
    assertTrue(
        errors.contains(UPGRADE_MESSAGE), "Output does not contain '" + UPGRADE_MESSAGE + "'");
  }

  @Test
  void ensureThatApplicationStartsWithAgentOnJava8() throws InterruptedException, IOException {
    ensureThatApplicationStartsWithoutWarning("8");
  }

  @Test
  void ensureThatApplicationStartsWithAgentOnJava11() throws InterruptedException, IOException {
    ensureThatApplicationStartsWithoutWarning("11");
  }

  private static void ensureThatApplicationStartsWithoutWarning(String version)
      throws InterruptedException, IOException {
    String expectedMessage = "Woho! Started on Java " + version;
    Process process =
        startAndWaitForJvmWithAgentForJava("JAVA_" + version + "_HOME", expectedMessage);
    int exitCode = process.waitFor();
    List<String> output = getLines(process.getInputStream());
    List<String> errors = getLines(process.getErrorStream());
    logProcessOutput(output, errors);
    assertEquals(0, exitCode, "Command failed with unexpected exit code");
    assertTrue(
        output.contains(expectedMessage), "Output does not contain '" + expectedMessage + "'");
    assertFalse(
        errors.stream().anyMatch(WARNING_PATTERN.asPredicate()),
        "Output contains unexpected line matching '" + WARNING_PATTERN + "'");
    assertFalse(
        errors.contains(UPGRADE_MESSAGE),
        "Output contains unexpected line '" + UPGRADE_MESSAGE + "'");
  }

  private static Process startAndWaitForJvmWithAgentForJava(String javaHomeEnv, String message)
      throws IOException {
    String javaHome = System.getenv(javaHomeEnv);
    checkFile(javaHome, javaHomeEnv);
    String javaAgent = System.getProperty("test.published.dependencies.agent");
    checkFile(javaAgent, "test.published.dependencies.agent");
    String jarFile = System.getProperty("test.published.dependencies.jar");
    checkFile(jarFile, "test.published.dependencies.jar");

    List<String> commandLine = new ArrayList<>();
    commandLine.add(Paths.get(javaHome).resolve("bin").resolve("java").toString());
    commandLine.add("-Xmx256M");
    commandLine.add("-javaagent:" + javaAgent);
    commandLine.add("-jar");
    commandLine.add(jarFile);
    commandLine.add(message);
    ProcessBuilder builder = new ProcessBuilder(commandLine);
    builder.environment().put("JAVA_HOME", javaHome);
    Process process = builder.start();
    return process;
  }

  private static void checkFile(String file, String name) {
    assertNotNull(file, name + " should be set");
    assertFalse(file.isEmpty(), name + " should not be empty ");
    assertTrue(Files.exists(Paths.get(file)), name + " [" + file + "] should be an existing path");
  }

  private static List<String> getLines(InputStream inputStream) {
    return new BufferedReader(new InputStreamReader(inputStream))
        .lines()
        .collect(Collectors.toList());
  }

  private static void logProcessOutput(List<String> output, List<String> errors) {
    System.out.println("-------------------");
    System.out.println("Sub process output:");
    output.forEach(System.out::println);
    System.out.println("-------------------");
    System.out.println("Sub process errors:");
    errors.forEach(System.out::println);
    System.out.println("-------------------");
    System.out.println("Sub process done.");
  }
}
