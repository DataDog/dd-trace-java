package datadog.trace.civisibility.source;

import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class NoOpSourcePathResolver implements SourcePathResolver {

  public static final SourcePathResolver INSTANCE = new NoOpSourcePathResolver();

  @Override
  public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
    return Collections.emptyList();
  }

  @Override
  public Collection<String> getResourcePaths(@Nullable String relativePath) {
    return Collections.emptyList();
  }
}
