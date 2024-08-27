package datadog.trace.civisibility.coverage.instrumentation.store;

import datadog.communication.util.IOThrowingFunction;
import datadog.trace.civisibility.config.JvmInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CoreJvmClassReader {

  private static final String JAVA_BASE_MODULE_RELATIVE_PATH = "jmods/java.base.jmod";

  // java.home contains the "/jre" segment for Java 8 distributions
  private static final String RT_JAR_RELATIVE_PATH = "lib/rt.jar";

  /**
   * Performs an action on the bytecode of a core JVM class
   *
   * @param jvm The JVM distribution
   * @param className The name of the class (as returned by {@link Class#getName}, e.g. {@code
   *     java.lang.Thread})
   * @param action The action to be performed on the class bytecode stream
   * @return The return value of the action
   * @param <T> The type of the return value
   * @throws IOException If the stream could not be retrieved
   */
  public <T> T withClassStream(
      JvmInfo jvm, String className, IOThrowingFunction<InputStream, T> action) throws IOException {
    if (jvm.isModular()) {
      return withClassStreamModularJdk(jvm.getHome(), className, action);
    } else {
      return withClassStreamPreModularJdk(jvm.getHome(), className, action);
    }
  }

  private <T> T withClassStreamModularJdk(
      Path jvmHome, String className, IOThrowingFunction<InputStream, T> action)
      throws IOException {
    Path javaBaseModule = jvmHome.resolve(JAVA_BASE_MODULE_RELATIVE_PATH);
    return withZipEntry(
        javaBaseModule, "classes/" + className.replace('.', '/') + ".class", action);
  }

  private <T> T withClassStreamPreModularJdk(
      Path jvmHome, String className, IOThrowingFunction<InputStream, T> action)
      throws IOException {
    Path rtJar = jvmHome.resolve(RT_JAR_RELATIVE_PATH);
    return withZipEntry(rtJar, className.replace('.', '/') + ".class", action);
  }

  private static <T> T withZipEntry(
      Path zipFilePath, String entryName, IOThrowingFunction<InputStream, T> action)
      throws IOException {
    try (ZipFile zipFile = new ZipFile(zipFilePath.toFile())) {
      ZipEntry entry = zipFile.getEntry(entryName);
      if (entry == null) {
        throw new IOException("Entry " + entryName + " not found in zip file " + zipFilePath);
      }
      try (InputStream entryStream = zipFile.getInputStream(entry)) {
        return action.apply(entryStream);
      }
    }
  }
}
