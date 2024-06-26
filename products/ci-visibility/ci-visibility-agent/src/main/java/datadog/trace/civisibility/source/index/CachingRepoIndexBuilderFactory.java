package datadog.trace.civisibility.source.index;

import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.nio.file.FileSystem;

public class CachingRepoIndexBuilderFactory implements RepoIndexProvider.Factory {

  private final DDCache<Pair<String, String>, RepoIndexProvider> cache =
      DDCaches.newFixedSizeCache(8);
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
  public RepoIndexProvider create(String repoRoot, String scanRoot) {
    Pair<String, String> key = Pair.of(repoRoot, scanRoot);
    return cache.computeIfAbsent(key, this::doCreate);
  }

  private RepoIndexProvider doCreate(Pair<String, String> key) {
    String repoRoot = key.getLeft();
    String scanRoot = key.getRight();
    return new RepoIndexBuilder(
        config, repoRoot, scanRoot, packageResolver, resourceResolver, fileSystem);
  }
}
