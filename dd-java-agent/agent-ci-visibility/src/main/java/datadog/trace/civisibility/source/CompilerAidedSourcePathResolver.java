package datadog.trace.civisibility.source;

import datadog.compiler.utils.CompilerUtils;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CompilerAidedSourcePathResolver implements SourcePathResolver {

  private final String repoRoot;

  public CompilerAidedSourcePathResolver(String repoRoot) {
    this.repoRoot = repoRoot;
  }

  @Nullable
  @Override
  public String getSourcePath(@Nonnull Class<?> c) {
    String absoluteSourcePath = CompilerUtils.getSourcePath(c);
    if (absoluteSourcePath != null && absoluteSourcePath.startsWith(repoRoot)) {
      return absoluteSourcePath.substring(repoRoot.length());
    } else {
      return null;
    }
  }

  @Nullable
  @Override
  public String getResourcePath(String relativePath) {
    return null;
  }
}
