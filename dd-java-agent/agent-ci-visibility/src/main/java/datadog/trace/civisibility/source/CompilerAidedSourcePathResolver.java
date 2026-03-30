package datadog.trace.civisibility.source;

import datadog.compiler.utils.CompilerUtils;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CompilerAidedSourcePathResolver implements SourcePathResolver {

  private final String repoRoot;

  public CompilerAidedSourcePathResolver(String repoRoot) {
    this.repoRoot = repoRoot.endsWith(File.separator) ? repoRoot : repoRoot + File.separator;
  }

  @Nonnull
  @Override
  public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
    String absoluteSourcePath = CompilerUtils.getSourcePath(c);
    if (absoluteSourcePath != null && absoluteSourcePath.startsWith(repoRoot)) {
      return Collections.singletonList(absoluteSourcePath.substring(repoRoot.length()));
    } else {
      return Collections.emptyList();
    }
  }

  @Override
  public @Nullable Collection<String> getResourcePaths(String relativePath) {
    return Collections.emptyList();
  }
}
