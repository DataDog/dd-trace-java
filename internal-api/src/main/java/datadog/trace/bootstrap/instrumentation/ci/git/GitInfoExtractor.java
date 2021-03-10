package datadog.trace.bootstrap.instrumentation.ci.git;

public interface GitInfoExtractor {

  GitInfo headCommit(final String gitFolder);
}
