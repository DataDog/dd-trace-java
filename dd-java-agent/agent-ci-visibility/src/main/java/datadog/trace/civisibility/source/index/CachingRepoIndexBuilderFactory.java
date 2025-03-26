package datadog.trace.civisibility.source.index;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.nio.file.FileSystem;
import javax.annotation.Nullable;

public class CachingRepoIndexBuilderFactory implements RepoIndexProvider.Factory {

  private final DDCache<String, RepoIndexProvider> cache = DDCaches.newFixedSizeCache(8);
  private final Config config;
  private final PackageResolver packageResolver;
  private final ResourceResolver resourceResolver;
  private final FileSystem fileSystem;

  public CachingRepoIndexBuilderFactory(
      Config config,
      PackageResolver packageResolver,
      ResourceResolver resourceResolver,
      FileSystem fileSystem) {
    this.config = config;
    this.packageResolver = packageResolver;
    this.resourceResolver = resourceResolver;
    this.fileSystem = fileSystem;
  }

  @Override
  public RepoIndexProvider create(@Nullable String repoRoot) {
    if (repoRoot == null) {
      return () -> RepoIndex.EMPTY;
    }
    return cache.computeIfAbsent(repoRoot, this::doCreate);
  }

  private RepoIndexProvider doCreate(String repoRoot) {
    return new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem);
  }
}
