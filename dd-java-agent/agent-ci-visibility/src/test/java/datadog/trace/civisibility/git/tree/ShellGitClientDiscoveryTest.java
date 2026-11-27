package datadog.trace.civisibility.git.tree;

import static datadog.communication.util.IOUtils.copyFolder;
import static datadog.trace.test.util.ForkedTestUtils.childJvmArguments;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.util.IOUtils;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.civisibility.utils.ShellCommandExecutor.ShellCommandFailedException;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class ShellGitClientDiscoveryTest {
  private static final String ROOT_PREFIX = "ROOT=";
  @TempDir Path workspace;

  @TableTest({
    "Scenario                 | nested | ceiling | expected",
    "enclosing repository     | false  | false   | outer   ",
    "bounded vendored sources | false  | true    | none    ",
    "bounded nested checkout  | true   | true    | nested  "
  })
  void respectsGitDiscoveryFromTheProvidedDirectory(
      boolean nested, boolean ceiling, String expected) throws Exception {
    Path gitFixture = Paths.get(getClass().getResource("/ci/git/with_pack/git").toURI());
    copyFolder(gitFixture, Files.createDirectory(workspace.resolve(".git")));
    Path vendor = Files.createDirectory(workspace.resolve("vendor"));
    Path project = Files.createDirectory(vendor.resolve("project"));
    if (nested) {
      copyFolder(gitFixture, Files.createDirectory(project.resolve(".git")));
    }
    Path tested = Files.createDirectory(project.resolve("module"));

    // A manifest classpath avoids command-line length limits when forking on Windows.
    Set<String> classpath = new LinkedHashSet<>();
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      classpath.add(new File(entry).toURI().toString());
    }
    for (ClassLoader loader = getClass().getClassLoader();
        loader != null;
        loader = loader.getParent()) {
      if (loader instanceof URLClassLoader) {
        for (URL entry : ((URLClassLoader) loader).getURLs()) {
          classpath.add(entry.toExternalForm());
        }
      }
    }
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, String.join(" ", classpath));
    Path pathingJar = workspace.resolve("classpath.jar");
    try (JarOutputStream ignored =
        new JarOutputStream(Files.newOutputStream(pathingJar), manifest)) {
      // The classpath is entirely in the manifest.
    }
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(childJvmArguments());
    command.add("-cp");
    command.add(pathingJar.toString());
    command.add(DiscoveryProbe.class.getName());
    command.add(tested.toString());
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().remove("GIT_DIR");
    builder.environment().remove("GIT_WORK_TREE");
    builder.environment().remove("GIT_CEILING_DIRECTORIES");
    if (ceiling) {
      builder.environment().put("GIT_CEILING_DIRECTORIES", vendor.toString());
    }
    Process process = builder.start();
    try {
      assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Git discovery probe timed out");
      String output = IOUtils.readFully(process.getInputStream(), UTF_8).trim();
      assertEquals(0, process.exitValue(), output);
      assertTrue(output.contains(ROOT_PREFIX), output);
      String actual = output.substring(output.lastIndexOf(ROOT_PREFIX) + ROOT_PREFIX.length());
      if ("none".equals(expected)) {
        assertEquals("NONE", actual);
      } else {
        assertEquals(
            ("nested".equals(expected) ? project : workspace).toRealPath(),
            Paths.get(actual).toRealPath());
      }
    } finally {
      process.destroyForcibly();
    }
  }

  public static final class DiscoveryProbe {
    public static void main(String[] arguments) throws Exception {
      ShellGitClient client =
          new ShellGitClient(
              NoOpMetricCollector.INSTANCE, arguments[0], "1 month ago", 1000, 10_000);
      try {
        System.out.println(ROOT_PREFIX + client.getRepoRoot());
      } catch (ShellCommandFailedException expected) {
        System.out.println(ROOT_PREFIX + "NONE");
      }
    }
  }
}
