package datadog.trace.agent.tooling.profiler;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.jar.JarFile;

public final class EnvironmentChecker {
  @SuppressForbidden
  public static boolean checkEnvironment(String temp) {
    if (!JavaVirtualMachine.isJavaVersionAtLeast(8)) {
      System.out.println("Profiler requires Java 8 or newer");
      return false;
    }
    System.out.println(
        "Using Java version: "
            + JavaVirtualMachine.getRuntimeVersion()
            + " ("
            + System.getProperty("java.home")
            + ")");
    System.out.println("Running as user: " + System.getProperty("user.name"));
    boolean result = false;
    result |= checkJFR();
    result |= checkDdprof();
    if (!result) {;
      System.out.println("Profiler is not supported on this JVM.");
      return false;
    } else {
      System.out.println("Profiler is supported on this JVM.");
    }
    System.out.println();
    if (!checkTempLocation(temp)) {
      System.out.println(
          "Profiler will not work properly due to issues with temp directory location.");
      return false;
    } else {
      if (!temp.equals(System.getProperty("java.io.tmpdir"))) {
        System.out.println(
            "! Make sure to add '-Ddd.profiling.tempdir=" + temp + "' to your JVM command line !");
      }
    }
    System.out.println("Profiler is ready to be used.");
    return true;
  }

  @SuppressForbidden
  private static boolean checkJFR() {
    if (JavaVirtualMachine.isOracleJDK8()) {
      System.out.println(
          "JFR is commercial feature in Oracle JDK 8. Make sure you have the right license.");
      return true;
    } else if (JavaVirtualMachine.isJ9()) {
      System.out.println("JFR is not supported on J9 JVM.");
      return false;
    } else {
      System.out.println("JFR is supported on " + JavaVirtualMachine.getRuntimeVersion());
      return true;
    }
  }

  @SuppressForbidden
  private static boolean checkDdprof() {
    if (!OperatingSystem.isLinux()) {
      System.out.println("Datadog profiler is only supported on Linux.");
      return false;
    } else {
      System.out.println(
          "Datadog profiler is supported on " + JavaVirtualMachine.getRuntimeVersion());
      return true;
    }
  }

  @SuppressForbidden
  private static boolean checkTempLocation(String temp) {
    // Check if the temp directory is writable
    if (temp == null || temp.isEmpty()) {
      System.out.println("Temp directory is not specified.");
      return false;
    }

    System.out.println("Checking temporary directory: " + temp);

    Path base = Paths.get(temp);
    if (!Files.exists(base)) {
      System.out.println("Temporary directory does not exist: " + base);
      return false;
    }
    Path target = base.resolve("dd-profiler").normalize();
    boolean rslt = true;
    Set<String> supportedViews = FileSystems.getDefault().supportedFileAttributeViews();
    boolean isPosix = supportedViews.contains("posix");
    try {
      if (isPosix) {
        Files.createDirectories(
            target,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      } else {
        // non-posix, eg. Windows - let's rely on the created folders being world-writable
        Files.createDirectories(target);
      }
      System.out.println("Temporary directory is writable: " + target);
      rslt &= checkCreateTempFile(target);
      rslt &= checkLoadLibrary(target);
    } catch (Exception e) {
      System.out.println("Unable to create temp directory in location " + temp);
      if (isPosix) {
        try {
          System.out.println(
              "Base dir: "
                  + base
                  + " ["
                  + PosixFilePermissions.toString(Files.getPosixFilePermissions(base))
                  + "]");
        } catch (IOException ignored) {
          // never happens
        }
      }
      System.out.println("Error: " + e);
    } finally {
      if (Files.exists(target)) {
        try {
          Files.walkFileTree(
              target,
              new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                  return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                  Files.delete(file);
                  return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc)
                    throws IOException {
                  return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                  Files.delete(dir);
                  return FileVisitResult.CONTINUE;
                }
              });
        } catch (IOException ignored) {
          // should never happen
        }
      }
    }
    return rslt;
  }

  @SuppressForbidden
  private static boolean checkCreateTempFile(Path target) {
    // create a file to check if the directory is writable
    try {
      System.out.println("Attempting to create a test file in: " + target);
      Path testFile = target.resolve("testfile");
      Files.createFile(testFile);
      System.out.println("Test file created: " + testFile);
      return true;
    } catch (Exception e) {
      System.out.println("Unable to create test file in temp directory " + target);
      System.out.println("Error: " + e);
    }
    return false;
  }

  @SuppressForbidden
  private static boolean checkLoadLibrary(Path target) {
    if (!OperatingSystem.isLinux()) {
      // we are loading the native library only on linux
      System.out.println("Skipping native library check on non-linux platform");
      return true;
    }
    boolean rslt = true;
    try {
      rslt &= extractSoFromJar(target);
      if (rslt) {
        Path libFile = target.resolve("libjavaProfiler.so");
        System.out.println("Attempting to load native library from: " + libFile);
        System.load(libFile.toString());
        System.out.println("Native library loaded successfully");
      }
      return true;
    } catch (Throwable t) {
      System.out.println("Unable to load native library in temp directory " + target);
      System.out.println("Error: " + t);
      return false;
    }
  }

  @SuppressForbidden
  private static boolean extractSoFromJar(Path target) throws Exception {
    URL jarUrl = EnvironmentChecker.class.getProtectionDomain().getCodeSource().getLocation();
    try (JarFile jarFile = new JarFile(new File(jarUrl.toURI()))) {
      return jarFile.stream()
          .filter(e -> e.getName().contains("libjavaProfiler.so"))
          .filter(
              e ->
                  e.getName()
                          .contains(OperatingSystem.isAarch64() ? "/linux-arm64/" : "/linux-x64/")
                      && (!OperatingSystem.isMusl() || e.getName().contains("-musl")))
          .findFirst()
          .map(
              e -> {
                try {
                  Path soFile = target.resolve("libjavaProfiler.so");
                  Files.createDirectories(soFile.getParent());
                  Files.copy(jarFile.getInputStream(e), soFile);
                  System.out.println("Native library extracted to: " + soFile);
                  return true;
                } catch (Throwable t) {
                  System.out.println("Failed to extract or load native library");
                  System.out.println("Error: " + t);
                }
                return false;
              })
          .orElse(Boolean.FALSE);
    }
  }
}
