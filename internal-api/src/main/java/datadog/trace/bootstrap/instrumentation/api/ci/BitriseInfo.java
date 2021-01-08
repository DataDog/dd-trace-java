package datadog.trace.bootstrap.instrumentation.api.ci;

public class BitriseInfo extends CIProviderInfo {

  public static final String BITRISE = "BITRISE_BUILD_SLUG";
  public static final String BITRISE_PROVIDER_NAME = "bitrise";
  public static final String BITRISE_PIPELINE_ID = "BITRISE_BUILD_SLUG";
  public static final String BITRISE_PIPELINE_NAME = "BITRISE_APP_TITLE";
  public static final String BITRISE_PIPELINE_NUMBER = "BITRISE_BUILD_NUMBER";
  public static final String BITRISE_PIPELINE_URL = "BITRISE_BUILD_URL";
  public static final String BITRISE_WORKSPACE_PATH = "BITRISE_SOURCE_DIR";
  public static final String BITRISE_GIT_REPOSITORY_URL = "GIT_REPOSITORY_URL";
  public static final String BITRISE_GIT_PR_COMMIT = "BITRISE_GIT_COMMIT";
  public static final String BITRISE_GIT_COMMIT = "GIT_CLONE_COMMIT_HASH";
  public static final String BITRISE_GIT_PR_BRANCH = "BITRISEIO_GIT_BRANCH_DEST";
  public static final String BITRISE_GIT_BRANCH = "BITRISE_GIT_BRANCH";
  public static final String BITRISE_GIT_TAG = "BITRISE_GIT_TAG";

  BitriseInfo() {
    final String gitTag = normalizeRef(System.getenv(BITRISE_GIT_TAG));

    this.ciTags =
        new CITagsBuilder()
            .withCiProviderName(BITRISE_PROVIDER_NAME)
            .withCiPipelineId(System.getenv(BITRISE_PIPELINE_ID))
            .withCiPipelineName(System.getenv(BITRISE_PIPELINE_NAME))
            .withCiPipelineNumber(System.getenv(BITRISE_PIPELINE_NUMBER))
            .withCiPipelineUrl(System.getenv(BITRISE_PIPELINE_URL))
            .withCiWorkspacePath(expandTilde(System.getenv(BITRISE_WORKSPACE_PATH)))
            .withGitRepositoryUrl(filterSensitiveInfo(System.getenv(BITRISE_GIT_REPOSITORY_URL)))
            .withGitCommit(buildGitCommit())
            .withGitBranch(buildGitBranch(gitTag))
            .withGitTag(gitTag)
            .build();
  }

  private String buildGitBranch(final String gitTag) {
    if (gitTag != null) {
      return null;
    }

    final String fromBranch = System.getenv(BITRISE_GIT_PR_BRANCH);
    if (fromBranch != null && !fromBranch.isEmpty()) {
      return normalizeRef(fromBranch);
    } else {
      return normalizeRef(System.getenv(BITRISE_GIT_BRANCH));
    }
  }

  private String buildGitCommit() {
    final String fromCommit = System.getenv(BITRISE_GIT_PR_COMMIT);
    if (fromCommit != null && !fromCommit.isEmpty()) {
      return fromCommit;
    } else {
      return System.getenv(BITRISE_GIT_COMMIT);
    }
  }
}
