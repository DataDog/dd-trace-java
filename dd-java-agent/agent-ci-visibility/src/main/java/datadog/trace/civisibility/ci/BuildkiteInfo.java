package datadog.trace.civisibility.ci;

import static datadog.json.JsonMapper.toJson;
import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;

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
  private static final String BUILDKITE_PULL_REQUEST_NUMBER = "BUILDKITE_PULL_REQUEST";
  private static final String BUILDKITE_PULL_REQUEST_BASE_BRANCH =
      "BUILDKITE_PULL_REQUEST_BASE_BRANCH";

  private final CiEnvironment environment;

  BuildkiteInfo(CiEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        filterSensitiveInfo(environment.get(BUILDKITE_GIT_REPOSITORY_URL)),
        normalizeBranch(environment.get(BUILDKITE_GIT_BRANCH)),
        normalizeTag(environment.get(BUILDKITE_GIT_TAG)),
        new CommitInfo(
            environment.get(BUILDKITE_GIT_COMMIT),
            buildGitCommitAuthor(),
            PersonInfo.NOOP,
            environment.get(BUILDKITE_GIT_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    final String ciPipelineUrl = environment.get(BUILDKITE_BUILD_URL);

    return CIInfo.builder(environment)
        .ciProviderName(BUILDKITE_PROVIDER_NAME)
        .ciPipelineId(environment.get(BUILDKITE_PIPELINE_ID))
        .ciPipelineName(environment.get(BUILDKITE_PIPELINE_NAME))
        .ciPipelineNumber(environment.get(BUILDKITE_PIPELINE_NUMBER))
        .ciPipelineUrl(ciPipelineUrl)
        .ciJobId(environment.get(BUILDKITE_JOB_ID))
        .ciJobUrl(String.format("%s#%s", ciPipelineUrl, environment.get(BUILDKITE_JOB_ID)))
        .ciWorkspace(expandTilde(environment.get(BUILDKITE_WORKSPACE_PATH)))
        .ciNodeName(environment.get(BUILDKITE_AGENT_ID))
        .ciNodeLabels(buildCiNodeLabels())
        .ciEnvVars(BUILDKITE_PIPELINE_ID, BUILDKITE_JOB_ID)
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    if (isPullRequest()) {
      return new PullRequestInfo(
          normalizeBranch(environment.get(BUILDKITE_PULL_REQUEST_BASE_BRANCH)),
          null,
          CommitInfo.NOOP,
          environment.get(BUILDKITE_PULL_REQUEST_NUMBER));
    }
    return PullRequestInfo.EMPTY;
  }

  private boolean isPullRequest() {
    String pullRequest = environment.get(BUILDKITE_PULL_REQUEST_NUMBER);
    return pullRequest != null && !"false".equals(pullRequest);
  }

  private String buildCiNodeLabels() {
    List<String> labels = new ArrayList<>();
    for (Map.Entry<String, String> e : environment.get().entrySet()) {
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
        environment.get(BUILDKITE_GIT_AUTHOR_NAME), environment.get(BUILDKITE_GIT_AUTHOR_EMAIL));
  }

  @Override
  public Provider getProvider() {
    return Provider.BUILDKITE;
  }
}
