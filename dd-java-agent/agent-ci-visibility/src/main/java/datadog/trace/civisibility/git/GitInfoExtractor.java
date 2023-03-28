package datadog.trace.civisibility.git;

import datadog.trace.api.civisibility.git.GitInfo;

public interface GitInfoExtractor {

  GitInfo headCommit(final String gitFolder);
}
