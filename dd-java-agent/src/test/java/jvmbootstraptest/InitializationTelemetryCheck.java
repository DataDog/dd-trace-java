package jvmbootstraptest;

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;

import datadog.trace.agent.test.IntegrationTestUtils;
import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Basic sanity check that InitializationTelemetry is functioning
 *
 * <p>Checks edge cases where InitializationTelemetry is blocked by SecurityManagers
 */
public class InitializationTelemetryCheck {
  public static void main(String[] args) throws Exception {
    // Emulates the real application performing work in main().
    // Start sub-process to generate one trace.
    try {
      ProcessBuilder builder = new ProcessBuilder("echo");
      Process process = builder.start();
      process.waitFor(5, TimeUnit.SECONDS);
    } catch (SecurityException se) {
      // Ignore security exceptions, as it can be part of strict security manager test.
    }

    // That should give enough time to send initial telemetry and traces.
    Thread.sleep(2000);
  }

  /** Blocks the loading of the agent bootstrap */
  public static class BlockAgentLoading extends TestSecurityManager {
    @Override
    protected boolean checkFileReadPermission(FilePermission perm, Object ctx, String filePath) {
      // NOTE: Blocking classes doesn't have the desired effect
      if (filePath.endsWith(".jar") && filePath.contains("dd-java-agent")) return false;

      return super.checkFileReadPermission(perm, ctx, filePath);
    }
  }

  /** Intended to break agent initialization */
  public static class BlockByteBuddy extends TestSecurityManager {
    @Override
    protected boolean checkOtherRuntimePermission(
        RuntimePermission perm, Object ctx, String permName) {
      switch (permName) {
        case "net.bytebuddy.createJavaDispatcher":
          return false;

        default:
          return super.checkOtherRuntimePermission(perm, ctx, permName);
      }
    }
  }

  public static class BlockForwarderEnvVar extends TestSecurityManager {
    @Override
    protected boolean checkRuntimeEnvironmentAccess(
        RuntimePermission perm, Object ctx, String envVar) {
      switch (envVar) {
        case "DD_TELEMETRY_FORWARDER_PATH":
          return false;

        default:
          return super.checkRuntimeEnvironmentAccess(perm, ctx, envVar);
      }
    }
  }

  public static class BlockForwarderExecution extends TestSecurityManager {
    @Override
    protected boolean checkFileExecutePermission(FilePermission perm, Object ctx, String filePath) {
      return false;
    }
  }

  public static Result runTestJvm(Class<? extends TestSecurityManager> securityManagerClass)
      throws Exception {
    return runTestJvm(securityManagerClass, DEFAULT_TRACE_AGENT_PORT);
  }

  public static Result runTestJvm(
      Class<? extends TestSecurityManager> securityManagerClass, int port) throws Exception {

    File jarFile =
        IntegrationTestUtils.createJarFileWithClasses(requiredClasses(securityManagerClass));

    File forwarderFile =
        createTempFile("forwarder", "sh", PosixFilePermissions.fromString("rwxr--r--"));
    File outputFile = new File(forwarderFile.getAbsoluteFile() + ".out");

    List<String> jvmArgs = new ArrayList<>();
    jvmArgs.add("-Ddd.trace.agent.port=" + port);
    if (securityManagerClass != null) {
      jvmArgs.add("-Djava.security.manager=" + securityManagerClass.getName());
    }

    write(
        forwarderFile,
        "#!/usr/bin/env bash\n",
        "echo \"$1	$(cat -)\" >> " + outputFile.getAbsolutePath() + "\n");

    try {
      int exitCode =
          IntegrationTestUtils.runOnSeparateJvm(
              InitializationTelemetryCheck.class.getName(),
              jvmArgs,
              Collections.emptyList(),
              InitializationTelemetryCheck.envVars(forwarderFile),
              jarFile,
              true);

      return new Result(exitCode, read(outputFile));
    } finally {
      delete(jarFile, forwarderFile, outputFile);
    }
  }

  static File createTempFile(String baseName, String extension, Set<PosixFilePermission> perms)
      throws IOException {
    Path path =
        Files.createTempFile(
            baseName + "-integration-telemetry-check",
            "." + extension,
            PosixFilePermissions.asFileAttribute(perms));
    File file = path.toFile();
    file.deleteOnExit();
    return file;
  }

  static void write(File file, String... lines) throws IOException {
    Files.write(file.toPath(), Arrays.asList(lines));
  }

  static String read(File file) {
    try {
      return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }

  static void delete(File... tempFiles) {
    for (File file : tempFiles) {
      file.delete();
    }
  }

  public static Class<?>[] requiredClasses(
      Class<? extends TestSecurityManager> securityManagerClass) {

    if (securityManagerClass == null) {
      return new Class<?>[] {
        InitializationTelemetryCheck.class, InitializationTelemetryCheck.Result.class
      };
    } else {
      return new Class<?>[] {
        InitializationTelemetryCheck.class,
        InitializationTelemetryCheck.Result.class,
        securityManagerClass,
        TestSecurityManager.class,
        CustomSecurityManager.class
      };
    }
  }

  public static Map<String, String> envVars(File forwarderFile) {
    Map<String, String> envVars = new HashMap<>();
    envVars.put("DD_TELEMETRY_FORWARDER_PATH", forwarderFile.getAbsolutePath());
    return envVars;
  }

  public static final class Result {
    public final int exitCode;
    public final String telemetryJson;

    public Result(int exitCode, String telemetryJson) {
      this.exitCode = exitCode;
      this.telemetryJson = telemetryJson;
    }
  }
}
