package datadog.trace.civisibility.source;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class NoOpSourcePathResolver implements SourcePathResolver {

  public static final SourcePathResolver INSTANCE = new NoOpSourcePathResolver();

  @Nullable
  @Override
  public String getSourcePath(@Nonnull Class<?> c) {
    return null;
  }

  @Nullable
  @Override
  public String getResourcePath(@Nullable String relativePath) {
    return null;
  }
}
