package datadog.trace.civisibility.config;

import datadog.trace.civisibility.utils.ShellCommandExecutor;
import datadog.trace.util.ProcessUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JvmInfoFactoryImpl implements JvmInfoFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(JvmInfoFactoryImpl.class);

  private static final int JVM_VERSION_LAUNCH_TIMEOUT = 5_000;

  @Nonnull
  @Override
  public JvmInfo getJvmInfo(@Nullable Path jvmExecutablePath) {
    String currentJvm =
        ProcessUtils.getCurrentJvmPath(); // might be home dir or full executable path
    // if we cannot determine forked JVM,
    // we assume it is the same as current one,
    // which is the most common case
    if (jvmExecutablePath == null
        || currentJvm != null && jvmExecutablePath.startsWith(currentJvm)) {
      return JvmInfo.CURRENT_JVM;
    } else {
      return doGetJvmInfo(jvmExecutablePath);
    }
  }

  static JvmInfo doGetJvmInfo(Path jvmExecutablePath) {
    Path jvmExecutableFolder = jvmExecutablePath.getParent();
    ShellCommandExecutor commandExecutor =
        new ShellCommandExecutor(jvmExecutableFolder.toFile(), JVM_VERSION_LAUNCH_TIMEOUT);
    try {
      return commandExecutor.executeCommandReadingError(
          new JvmVersionOutputParser(), "./java", "-XshowSettings:properties", "-version");

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.warn(
          "Interrupted while waiting for JVM runtime info for {}, assuming {}",
          jvmExecutablePath,
          JvmInfo.CURRENT_JVM);
      return JvmInfo.CURRENT_JVM;

    } catch (Exception e) {
      LOGGER.warn(
          "Could not determine JVM runtime info for {}, assuming {}",
          jvmExecutablePath,
          JvmInfo.CURRENT_JVM,
          e);
      return JvmInfo.CURRENT_JVM;
    }
  }

  private static final class JvmVersionOutputParser
      implements ShellCommandExecutor.OutputParser<JvmInfo> {
    @Override
    public JvmInfo parse(InputStream inputStream) throws IOException {
      String name = null;
      String version = null;
      String vendor = null;

      BufferedReader bis =
          new BufferedReader(new InputStreamReader(inputStream, Charset.defaultCharset()));
      String line;
      while ((line = bis.readLine()) != null) {
        if (line.contains("java.runtime.name ")) {
          name = getPropertyValue(line);
        } else if (line.contains("java.version ")) {
          version = getPropertyValue(line);
        } else if (line.contains("java.vendor ")) {
          vendor = getPropertyValue(line);
        }
      }
      return new JvmInfo(name, version, vendor);
    }

    private String getPropertyValue(String line) {
      // format of the input is: "    property.name = propertyValue"
      return line.substring(line.indexOf('=') + 2);
    }
  }
}
