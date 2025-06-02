package jvmbootstraptest;

import datadog.trace.agent.test.IntegrationTestUtils;
import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Basic sanity check that InitializationTelemetry is functioning
 *
 * <p>Checks edge cases where InitializationTelemetry is blocked by SecurityManagers
 */
public class InitializationTelemetryCheck {
  public static void main(String[] args) throws InterruptedException {
    System.err.println(System.currentTimeMillis() + " DEBUG: main started");

    // Emulates the real application performing work in main().
    // That should give enough time to send initial telemetry from daemon thread.
    if (args.length > 0 && "sleep".equals(args[0])) {
      Thread.sleep(2000);
    }

    System.err.println(System.currentTimeMillis() + " DEBUG: main ended");
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

  public static final Result runTestJvm(Class<? extends TestSecurityManager> securityManagerClass)
      throws Exception {
    return runTestJvm(securityManagerClass, false, null);
  }

  public static final Result runTestJvm(
      Class<? extends TestSecurityManager> securityManagerClass, boolean printStreams)
      throws Exception {
    return runTestJvm(securityManagerClass, printStreams, null);
  }

  public static final Result runTestJvm(
      Class<? extends TestSecurityManager> securityManagerClass,
      boolean printStreams,
      String mainArgs)
      throws Exception {

    File jarFile =
        IntegrationTestUtils.createJarFileWithClasses(requiredClasses(securityManagerClass));

    File forwarderFile =
        createTempFile("forwarder", "sh", PosixFilePermissions.fromString("rwxr--r--"));
    File outputFile = new File(forwarderFile.getAbsoluteFile() + ".out");

    write(
        forwarderFile,
        "#!/usr/bin/env bash\n",
        "echo \"$1	$(cat -)\" >> " + outputFile.getAbsolutePath() + "\n");

    try {
      int exitCode =
          IntegrationTestUtils.runOnSeparateJvm(
              InitializationTelemetryCheck.class.getName(),
              InitializationTelemetryCheck.jvmArgs(securityManagerClass),
              InitializationTelemetryCheck.mainArgs(mainArgs),
              InitializationTelemetryCheck.envVars(forwarderFile),
              jarFile,
              printStreams);

      return new Result(exitCode, read(outputFile));
    } finally {
      delete(jarFile, forwarderFile, outputFile);
    }
  }

  static final File createTempFile(
      String baseName, String extension, Set<PosixFilePermission> perms) throws IOException {
    Path path =
        Files.createTempFile(
            baseName + "-integration-telemetry-check",
            "." + extension,
            PosixFilePermissions.asFileAttribute(perms));
    File file = path.toFile();
    file.deleteOnExit();
    return file;
  }

  static final void write(File file, String... lines) throws IOException {
    Files.write(file.toPath(), Arrays.asList(lines));
  }

  static final String read(File file) {
    try {
      return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }

  static final void delete(File... tempFiles) {
    for (File file : tempFiles) {
      file.delete();
    }
  }

  public static final Class<?>[] requiredClasses(
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

  public static final String[] jvmArgs(Class<? extends TestSecurityManager> securityManagerClass) {
    if (securityManagerClass == null) {
      return new String[] {};
    } else {
      return new String[] {"-Djava.security.manager=" + securityManagerClass.getName()};
    }
  }

  public static final String[] mainArgs(String args) {
    if (args == null) {
      return new String[] {};
    } else {
      return args.split(",");
    }
  }

  public static final Map<String, String> envVars(File forwarderFile) {
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
