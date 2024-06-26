package datadog.trace.civisibility.config;

import java.nio.file.Path;

public interface JvmInfoFactory {
  JvmInfo getJvmInfo(Path jvmExecutablePath);
}
