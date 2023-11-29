package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;
import static datadog.trace.util.Strings.toJson;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class BuildkiteInfo implements CIProviderInfo {

  // https://buildkite.com/docs/pipelines/environment-variables
  public static final String BUILDKITE = "BUILDKITE";
  public static final String BUILDKITE_PROVIDER_NAME = "buildkite";
  public static final String BUILDKITE_PIPELINE_ID = "BUILDKITE_BUILD_ID";
  public static final String BUILDKITE_PIPELINE_NAME = "BUILDKITE_PIPELINE_SLUG";
  public static final String BUILDKITE_PIPELINE_NUMBER = "BUILDKITE_BUILD_NUMBER";
  public static final String BUILDKITE_BUILD_URL = "BUILDKITE_BUILD_URL";
  public static final String BUILDKITE_JOB_ID = "BUILDKITE_JOB_ID";
  public static final String BUILDKITE_WORKSPACE_PATH = "BUILDKITE_BUILD_CHECKOUT_PATH";
  public static final String BUILDKITE_GIT_REPOSITORY_URL = "BUILDKITE_REPO";
  public static final String BUILDKITE_GIT_COMMIT = "BUILDKITE_COMMIT";
  public static final String BUILDKITE_GIT_BRANCH = "BUILDKITE_BRANCH";
  public static final String BUILDKITE_GIT_TAG = "BUILDKITE_TAG";
  public static final String BUILDKITE_GIT_MESSAGE = "BUILDKITE_MESSAGE";
  public static final String BUILDKITE_GIT_AUTHOR_NAME = "BUILDKITE_BUILD_AUTHOR";
  public static final String BUILDKITE_GIT_AUTHOR_EMAIL = "BUILDKITE_BUILD_AUTHOR_EMAIL";
  public static final String BUILDKITE_AGENT_ID = "BUILDKITE_AGENT_ID";
  private static final String BUILDKITE_CI_NODE_LABEL_PREFIX = "BUILDKITE_AGENT_META_DATA_";

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        filterSensitiveInfo(System.getenv(BUILDKITE_GIT_REPOSITORY_URL)),
        normalizeBranch(System.getenv(BUILDKITE_GIT_BRANCH)),
        normalizeTag(System.getenv(BUILDKITE_GIT_TAG)),
        new CommitInfo(
            System.getenv(BUILDKITE_GIT_COMMIT),
            buildGitCommitAuthor(),
            PersonInfo.NOOP,
            System.getenv(BUILDKITE_GIT_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    final String ciPipelineUrl = System.getenv(BUILDKITE_BUILD_URL);

    return CIInfo.builder()
        .ciProviderName(BUILDKITE_PROVIDER_NAME)
        .ciPipelineId(System.getenv(BUILDKITE_PIPELINE_ID))
        .ciPipelineName(System.getenv(BUILDKITE_PIPELINE_NAME))
        .ciPipelineNumber(System.getenv(BUILDKITE_PIPELINE_NUMBER))
        .ciPipelineUrl(ciPipelineUrl)
        .ciJobUrl(String.format("%s#%s", ciPipelineUrl, System.getenv(BUILDKITE_JOB_ID)))
        .ciWorkspace(expandTilde(System.getenv(BUILDKITE_WORKSPACE_PATH)))
        .ciNodeName(System.getenv(BUILDKITE_AGENT_ID))
        .ciNodeLabels(buildCiNodeLabels())
        .ciEnvVars(BUILDKITE_PIPELINE_ID, BUILDKITE_JOB_ID)
        .build();
  }

  private String buildCiNodeLabels() {
    List<String> labels = new ArrayList<>();
    for (Map.Entry<String, String> e : System.getenv().entrySet()) {
      String envVar = e.getKey();
      if (envVar.startsWith(BUILDKITE_CI_NODE_LABEL_PREFIX)) {
        String labelKey =
            envVar.substring(BUILDKITE_CI_NODE_LABEL_PREFIX.length()).toLowerCase(Locale.ROOT);
        String labelValue = e.getValue();
        labels.add(labelKey + ':' + labelValue);
      }
    }
    return !labels.isEmpty() ? toJson(labels) : null;
  }

  private PersonInfo buildGitCommitAuthor() {
    return new PersonInfo(
        System.getenv(BUILDKITE_GIT_AUTHOR_NAME), System.getenv(BUILDKITE_GIT_AUTHOR_EMAIL));
  }
}
