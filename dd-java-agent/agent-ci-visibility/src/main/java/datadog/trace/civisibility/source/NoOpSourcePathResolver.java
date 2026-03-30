package datadog.trace.civisibility.source;

import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class NoOpSourcePathResolver implements SourcePathResolver {

  public static final SourcePathResolver INSTANCE = new NoOpSourcePathResolver();

  @Nullable
  @Override
  public String getSourcePath(@Nonnull Class<?> c) {
    return null;
  }

  @Nonnull
  @Override
  public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
    return Collections.emptyList();
  }

  @Nullable
  @Override
  public String getResourcePath(@Nullable String relativePath) {
    return null;
  }
}
