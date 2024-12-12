package datadog.trace.civisibility.config;

import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface JvmInfoFactory {
  @Nonnull
  JvmInfo getJvmInfo(@Nullable Path jvmExecutablePath);
}
