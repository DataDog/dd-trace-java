package datadog.trace.civisibility.source.index;

import datadog.trace.api.Config;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.net.URL;
import java.nio.file.FileSystem;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class RepoIndexSourcePathResolver implements SourcePathResolver {

  private final String repoRoot;
  private final RepoIndexProvider indexProvider;

  public RepoIndexSourcePathResolver(String repoRoot, RepoIndexProvider indexProvider) {
    this.repoRoot = repoRoot;
    this.indexProvider = indexProvider;
  }

  RepoIndexSourcePathResolver(
      String repoRoot, SourceRootResolver sourceRootResolver, FileSystem fileSystem) {
    this.repoRoot = repoRoot;
    this.indexProvider = new RepoIndexBuilder(repoRoot, sourceRootResolver, fileSystem);
  }

  @Nullable
  @Override
  public String getSourcePath(@Nonnull Class<?> c) {
    if (Config.get().isCiVisibilitySourceDataRootCheckEnabled() && !isLocatedInsideRepository(c)
        || implementsContextAccessor(c)) {
      return null; // fast exit to avoid expensive index building
    }
    return indexProvider.getIndex().getSourcePath(c);
  }

  private boolean isLocatedInsideRepository(Class<?> c) {
    ProtectionDomain protectionDomain = c.getProtectionDomain();
    if (protectionDomain == null) {
      return false; // no source location data
    }
    CodeSource codeSource = protectionDomain.getCodeSource();
    if (codeSource == null) {
      return false; // no source location data
    }
    URL location = codeSource.getLocation();
    if (location == null) {
      return false; // no source location data
    }
    String file = location.getFile();
    if (file == null) {
      return false; // no source location data
    }
    return file.startsWith(repoRoot);
  }

  private static boolean implementsContextAccessor(Class<?> c) {
    for (Class<?> intf : c.getInterfaces()) {
      if ("datadog.trace.bootstrap.FieldBackedContextAccessor".equals(intf.getName())) {
        // dynamically generated accessor class for bytecode-injected field
        return true;
      }
    }
    return false;
  }
}
