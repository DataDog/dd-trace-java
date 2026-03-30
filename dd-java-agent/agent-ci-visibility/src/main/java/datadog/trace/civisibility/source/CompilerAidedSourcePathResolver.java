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

  @Nonnull
  @Override
  public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
    String path = getSourcePath(c);
    return path != null ? Collections.singletonList(path) : Collections.emptyList();
  }

  @Nullable
  @Override
  public String getResourcePath(String relativePath) {
    return null;
  }
}
