package datadog.trace.civisibility.ci;

import static datadog.json.JsonMapper.toJson;

import datadog.trace.api.DDTags;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.HashMap;
import java.util.Map;

public class CITagsProvider {

  private final GitInfoProvider gitInfoProvider;

  public CITagsProvider() {
    this(GitInfoProvider.INSTANCE);
  }

  CITagsProvider(GitInfoProvider gitInfoProvider) {
    this.gitInfoProvider = gitInfoProvider;
  }

  public Map<String, String> getCiTags(CIInfo ciInfo, PullRequestInfo pullRequestInfo) {
    String repoRoot = ciInfo.getCiWorkspace();
    GitInfo gitInfo = gitInfoProvider.getGitInfo(repoRoot);

    return new CITagsBuilder()
        .withCiProviderName(ciInfo.getCiProviderName())
        .withCiPipelineId(ciInfo.getCiPipelineId())
        .withCiPipelineName(ciInfo.getCiPipelineName())
        .withCiStageName(ciInfo.getCiStageName())
        .withCiJobName(ciInfo.getCiJobName())
        .withCiPipelineNumber(ciInfo.getCiPipelineNumber())
        .withCiPipelineUrl(ciInfo.getCiPipelineUrl())
        .withCiJorUrl(ciInfo.getCiJobUrl())
        .withCiWorkspacePath(ciInfo.getCiWorkspace())
        .withCiNodeName(ciInfo.getCiNodeName())
        .withCiNodeLabels(ciInfo.getCiNodeLabels())
        .withCiEnvVars(ciInfo.getCiEnvVars())
        .withAdditionalTags(ciInfo.getAdditionalTags())
        .withPullRequestBaseBranch(pullRequestInfo)
        .withPullRequestBaseBranchSha(pullRequestInfo)
        .withGitCommitHeadSha(pullRequestInfo)
        .withGitCommitHeadAuthorName(pullRequestInfo)
        .withGitCommitHeadAuthorEmail(pullRequestInfo)
        .withGitCommitHeadAuthorDate(pullRequestInfo)
        .withGitCommitHeadCommitterName(pullRequestInfo)
        .withGitCommitHeadCommitterEmail(pullRequestInfo)
        .withGitCommitHeadCommitterDate(pullRequestInfo)
        .withGitCommitHeadMessage(pullRequestInfo)
        .withPullRequestNumber(pullRequestInfo)
        .withGitRepositoryUrl(gitInfo)
        .withGitCommit(gitInfo)
        .withGitBranch(gitInfo)
        .withGitTag(gitInfo)
        .withGitCommitAuthorName(gitInfo)
        .withGitCommitAuthorEmail(gitInfo)
        .withGitCommitAuthorDate(gitInfo)
        .withGitCommitCommitterName(gitInfo)
        .withGitCommitCommitterEmail(gitInfo)
        .withGitCommitCommitterDate(gitInfo)
        .withGitCommitMessage(gitInfo)
        .build();
  }

  public static class CITagsBuilder {

    private final Map<String, String> ciTags = new HashMap<>();

    public CITagsBuilder withCiProviderName(final String ciProviderName) {
      return putTagValue(Tags.CI_PROVIDER_NAME, ciProviderName);
    }

    public CITagsBuilder withCiPipelineId(final String ciPipelineId) {
      return putTagValue(Tags.CI_PIPELINE_ID, ciPipelineId);
    }

    public CITagsBuilder withCiPipelineName(final String ciPipelineName) {
      return putTagValue(Tags.CI_PIPELINE_NAME, ciPipelineName);
    }

    public CITagsBuilder withCiPipelineNumber(final String ciPipelineNumber) {
      return putTagValue(Tags.CI_PIPELINE_NUMBER, ciPipelineNumber);
    }

    public CITagsBuilder withCiPipelineUrl(final String ciPipelineUrl) {
      return putTagValue(Tags.CI_PIPELINE_URL, ciPipelineUrl);
    }

    public CITagsBuilder withCiStageName(final String ciStageName) {
      return putTagValue(Tags.CI_STAGE_NAME, ciStageName);
    }

    public CITagsBuilder withCiJobName(final String ciJobName) {
      return putTagValue(Tags.CI_JOB_NAME, ciJobName);
    }

    public CITagsBuilder withCiJorUrl(final String ciJobUrl) {
      return putTagValue(Tags.CI_JOB_URL, ciJobUrl);
    }

    public CITagsBuilder withCiWorkspacePath(final String ciWorkspacePath) {
      return putTagValue(Tags.CI_WORKSPACE_PATH, ciWorkspacePath);
    }

    public CITagsBuilder withCiNodeName(final String ciNodeName) {
      return putTagValue(Tags.CI_NODE_NAME, ciNodeName);
    }

    public CITagsBuilder withCiNodeLabels(final String ciNodeLabels) {
      return putTagValue(Tags.CI_NODE_LABELS, ciNodeLabels);
    }

    public CITagsBuilder withCiEnvVars(final Map<String, String> ciEnvVars) {
      if (ciEnvVars == null || ciEnvVars.isEmpty()) {
        return this;
      }
      return putTagValue(DDTags.CI_ENV_VARS, toJson(ciEnvVars));
    }

    public CITagsBuilder withAdditionalTags(final Map<String, String> additionalTags) {
      if (additionalTags == null || additionalTags.isEmpty()) {
        return this;
      }
      for (Map.Entry<String, String> e : additionalTags.entrySet()) {
        putTagValue(e.getKey(), e.getValue());
      }
      return this;
    }

    public CITagsBuilder withPullRequestBaseBranch(final PullRequestInfo pullRequestInfo) {
      return putTagValue(
          Tags.GIT_PULL_REQUEST_BASE_BRANCH, pullRequestInfo.getPullRequestBaseBranch());
    }

    public CITagsBuilder withPullRequestBaseBranchSha(final PullRequestInfo pullRequestInfo) {
      return putTagValue(
          Tags.GIT_PULL_REQUEST_BASE_BRANCH_SHA, pullRequestInfo.getPullRequestBaseBranchSha());
    }

    public CITagsBuilder withGitCommitHeadSha(final PullRequestInfo pullRequestInfo) {
      return putTagValue(Tags.GIT_COMMIT_HEAD_SHA, pullRequestInfo.getHeadCommit().getSha());
    }

    public CITagsBuilder withGitCommitHeadAuthorName(final PullRequestInfo pullRequestInfo) {
      return putTagValue(
          Tags.GIT_COMMIT_HEAD_AUTHOR_NAME, pullRequestInfo.getHeadCommit().getAuthor().getName());
    }

    public CITagsBuilder withGitCommitHeadAuthorEmail(final PullRequestInfo pullRequestInfo) {
      return putTagValue(
          Tags.GIT_COMMIT_HEAD_AUTHOR_EMAIL,
          pullRequestInfo.getHeadCommit().getAuthor().getEmail());
    }

    public CITagsBuilder withGitCommitHeadAuthorDate(final PullRequestInfo pullRequestInfo) {
      return putTagValue(
          Tags.GIT_COMMIT_HEAD_AUTHOR_DATE,
          pullRequestInfo.getHeadCommit().getAuthor().getIso8601Date());
    }

    public CITagsBuilder withGitCommitHeadCommitterName(final PullRequestInfo pullRequestInfo) {
      return putTagValue(
          Tags.GIT_COMMIT_HEAD_COMMITTER_NAME,
          pullRequestInfo.getHeadCommit().getCommitter().getName());
    }

    public CITagsBuilder withGitCommitHeadCommitterEmail(final PullRequestInfo pullRequestInfo) {
      return putTagValue(
          Tags.GIT_COMMIT_HEAD_COMMITTER_EMAIL,
          pullRequestInfo.getHeadCommit().getCommitter().getEmail());
    }

    public CITagsBuilder withGitCommitHeadCommitterDate(final PullRequestInfo pullRequestInfo) {
      return putTagValue(
          Tags.GIT_COMMIT_HEAD_COMMITTER_DATE,
          pullRequestInfo.getHeadCommit().getCommitter().getIso8601Date());
    }

    public CITagsBuilder withGitCommitHeadMessage(final PullRequestInfo pullRequestInfo) {
      return putTagValue(
          Tags.GIT_COMMIT_HEAD_MESSAGE, pullRequestInfo.getHeadCommit().getFullMessage());
    }

    public CITagsBuilder withPullRequestNumber(final PullRequestInfo pullRequestInfo) {
      return putTagValue(Tags.PULL_REQUEST_NUMBER, pullRequestInfo.getPullRequestNumber());
    }

    public CITagsBuilder withGitRepositoryUrl(final GitInfo gitInfo) {
      return putTagValue(Tags.GIT_REPOSITORY_URL, gitInfo.getRepositoryURL());
    }

    public CITagsBuilder withGitCommit(final GitInfo gitInfo) {
      return putTagValue(Tags.GIT_COMMIT_SHA, gitInfo.getCommit().getSha());
    }

    public CITagsBuilder withGitBranch(final GitInfo gitInfo) {
      return putTagValue(Tags.GIT_BRANCH, gitInfo.getBranch());
    }

    public CITagsBuilder withGitTag(final GitInfo gitInfo) {
      return putTagValue(Tags.GIT_TAG, gitInfo.getTag());
    }

    public CITagsBuilder withGitCommitAuthorName(final GitInfo gitInfo) {
      return putTagValue(Tags.GIT_COMMIT_AUTHOR_NAME, gitInfo.getCommit().getAuthor().getName());
    }

    public CITagsBuilder withGitCommitAuthorEmail(final GitInfo gitInfo) {
      return putTagValue(Tags.GIT_COMMIT_AUTHOR_EMAIL, gitInfo.getCommit().getAuthor().getEmail());
    }

    public CITagsBuilder withGitCommitAuthorDate(final GitInfo gitInfo) {
      return putTagValue(
          Tags.GIT_COMMIT_AUTHOR_DATE, gitInfo.getCommit().getAuthor().getIso8601Date());
    }

    public CITagsBuilder withGitCommitCommitterName(final GitInfo gitInfo) {
      return putTagValue(
          Tags.GIT_COMMIT_COMMITTER_NAME, gitInfo.getCommit().getCommitter().getName());
    }

    public CITagsBuilder withGitCommitCommitterEmail(final GitInfo gitInfo) {
      return putTagValue(
          Tags.GIT_COMMIT_COMMITTER_EMAIL, gitInfo.getCommit().getCommitter().getEmail());
    }

    public CITagsBuilder withGitCommitCommitterDate(final GitInfo gitInfo) {
      return putTagValue(
          Tags.GIT_COMMIT_COMMITTER_DATE, gitInfo.getCommit().getCommitter().getIso8601Date());
    }

    public CITagsBuilder withGitCommitMessage(final GitInfo gitInfo) {
      return putTagValue(Tags.GIT_COMMIT_MESSAGE, gitInfo.getCommit().getFullMessage());
    }

    public Map<String, String> build() {
      return ciTags;
    }

    private CITagsBuilder putTagValue(final String tagKey, final String tagValue) {
      if (tagValue != null) {
        ciTags.put(tagKey, tagValue);
      }
      return this;
    }
  }
}
