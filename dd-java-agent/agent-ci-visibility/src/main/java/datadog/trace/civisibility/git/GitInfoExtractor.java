package datadog.trace.civisibility.git;

public interface GitInfoExtractor {

  GitInfo headCommit(final String gitFolder);
}
