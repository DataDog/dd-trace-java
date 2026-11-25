package datadog.trace.agent.integration.classloading;

import static datadog.trace.agent.test.IntegrationTestUtils.createJarFileWithClasses;
import static datadog.trace.agent.test.IntegrationTestUtils.getAgentJarLocation;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import jvmbootstraptest.PremainClassLoadingCheck;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

@Timeout(30)
class PremainClassLoadingTest {
  @TempDir Path directory;

  @TableTest({
    "Scenario           | appSec   | iast  | ciVisibility",
    "tracing            | inactive | false | false       ",
    "tracing and AppSec | true     | false | false       ",
    "tracing and IAST   | inactive | true  | false       ",
    "CI Visibility      | inactive | false | true        "
  })
  void defersGuardedClassesUntilTransformerRegistration(
      String appSec, boolean iast, boolean ciVisibility) throws Exception {
    File agent = new File(getAgentJarLocation());
    assertTrue(agent.isFile(), "The test must use the packaged agent JAR");
    Map<String, String> capabilities = new HashMap<>();
    capabilities.put("Can-Redefine-Classes", "true");
    capabilities.put("Can-Retransform-Classes", "true");
    File checker =
        createJarFileWithClasses(
            PremainClassLoadingCheck.class.getName(), capabilities, PremainClassLoadingCheck.class);
    Path output = directory.resolve("startup.log");
    Process process =
        new ProcessBuilder(
                new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-Ddd.appsec.enabled=" + appSec,
                "-Ddd.iast.enabled=" + iast,
                "-Ddd.civisibility.enabled=" + ciVisibility,
                "-Ddd.remote_config.enabled=false",
                "-Ddd.instrumentation.telemetry.enabled=false",
                "-Ddd.trace.startup.logs=false",
                "-Ddd.writer.type=LoggingWriter",
                "-javaagent:" + checker.getPath(),
                "-cp",
                checker.getPath() + File.pathSeparator + agent.getPath(),
                PremainClassLoadingCheck.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(process.waitFor(25, SECONDS), "Agent startup timed out");
      assertEquals(
          0, process.exitValue(), new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
    } finally {
      process.destroyForcibly();
    }
  }
}
