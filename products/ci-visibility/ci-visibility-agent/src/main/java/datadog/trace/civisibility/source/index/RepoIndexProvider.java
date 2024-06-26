package datadog.trace.civisibility.source.index;

public interface RepoIndexProvider {
  RepoIndex getIndex();

  interface Factory {
    RepoIndexProvider create(String repoRoot, String scanRoot);
  }
}
