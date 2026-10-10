package datadog.trace.civisibility.source;

import datadog.compiler.utils.CompilerUtils;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CompilerAidedSourcePathResolver implements SourcePathResolver {

  private final Path repoRoot;

  public CompilerAidedSourcePathResolver(String repoRoot) {
    this.repoRoot = Paths.get(repoRoot).normalize();
  }

  @Nonnull
  @Override
  public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
    String absoluteSourcePath = CompilerUtils.getSourcePath(c);
    if (absoluteSourcePath == null) {
      return Collections.emptyList();
    }
    try {
      Path sourcePath = Paths.get(absoluteSourcePath).normalize();
      return sourcePath.startsWith(repoRoot)
          ? Collections.singletonList(repoRoot.relativize(sourcePath).toString())
          : Collections.emptyList();
    } catch (InvalidPathException e) {
      return Collections.emptyList();
    }
  }

  @Override
  public @Nullable Collection<String> getResourcePaths(String relativePath) {
    return Collections.emptyList();
  }
}
