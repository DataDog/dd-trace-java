package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.util.Strings;

class BitBucketInfo implements CIProviderInfo {

  // https://support.atlassian.com/bitbucket-cloud/docs/variables-and-secrets/
  public static final String BITBUCKET = "BITBUCKET_BUILD_NUMBER";
  public static final String BITBUCKET_PROVIDER_NAME = "bitbucket";
  public static final String BITBUCKET_PIPELINE_ID = "BITBUCKET_PIPELINE_UUID";
  public static final String BITBUCKET_REPO_FULL_NAME = "BITBUCKET_REPO_FULL_NAME";
  public static final String BITBUCKET_BUILD_NUMBER = "BITBUCKET_BUILD_NUMBER";
  public static final String BITBUCKET_WORKSPACE_PATH = "BITBUCKET_CLONE_DIR";
  public static final String BITBUCKET_GIT_REPOSITORY_URL = "BITBUCKET_GIT_SSH_ORIGIN";
  public static final String BITBUCKET_HTTPS_REPOSITORY_URL = "BITBUCKET_GIT_HTTP_ORIGIN";
  public static final String BITBUCKET_GIT_COMMIT = "BITBUCKET_COMMIT";
  public static final String BITBUCKET_GIT_BRANCH = "BITBUCKET_BRANCH";
  public static final String BITBUCKET_GIT_TAG = "BITBUCKET_TAG";

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        getRepositoryURL(),
        normalizeBranch(System.getenv(BITBUCKET_GIT_BRANCH)),
        normalizeTag(System.getenv(BITBUCKET_GIT_TAG)),
        new CommitInfo(System.getenv(BITBUCKET_GIT_COMMIT)));
  }

  private static String getRepositoryURL() {
    String gitRepoUrl = System.getenv(BITBUCKET_GIT_REPOSITORY_URL);
    if (Strings.isNotBlank(gitRepoUrl)) {
      return filterSensitiveInfo(gitRepoUrl);
    }
    String httpsRepoUrl = System.getenv(BITBUCKET_HTTPS_REPOSITORY_URL);
    if (Strings.isNotBlank(httpsRepoUrl)) {
      return filterSensitiveInfo(httpsRepoUrl);
    }
    return null;
  }

  @Override
  public CIInfo buildCIInfo() {
    final String repo = System.getenv(BITBUCKET_REPO_FULL_NAME);
    final String number = System.getenv(BITBUCKET_BUILD_NUMBER);
    final String url = buildPipelineUrl(repo, number);

    return CIInfo.builder()
        .ciProviderName(BITBUCKET_PROVIDER_NAME)
        .ciPipelineId(buildPipelineId())
        .ciPipelineName(repo)
        .ciPipelineNumber(number)
        .ciPipelineUrl(url)
        .ciJobUrl(url)
        .ciWorkspace(expandTilde(System.getenv(BITBUCKET_WORKSPACE_PATH)))
        .build();
  }

  private String buildPipelineUrl(final String repo, final String number) {
    return String.format(
        "https://bitbucket.org/%s/addon/pipelines/home#!/results/%s", repo, number);
  }

  private String buildPipelineId() {
    String id = System.getenv(BITBUCKET_PIPELINE_ID);
    if (id != null) {
      id = Strings.replace(id, "{", "");
      id = Strings.replace(id, "}", "");
    }
    return id;
  }
}
