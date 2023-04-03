package datadog.trace.civisibility.git;

import datadog.trace.api.git.GitInfo;

public interface GitInfoExtractor {

  GitInfo headCommit(final String gitFolder);
}
