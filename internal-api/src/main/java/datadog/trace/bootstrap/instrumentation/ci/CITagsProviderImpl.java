package datadog.trace.bootstrap.instrumentation.ci;

import static datadog.trace.util.Strings.toJson;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.GitUtils;
import datadog.trace.bootstrap.instrumentation.ci.git.info.CILocalGitInfoBuilder;
import datadog.trace.bootstrap.instrumentation.ci.git.info.UserSuppliedGitInfoBuilder;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressForbidden
public class CITagsProviderImpl implements CITagsProvider {

  private static final Logger log = LoggerFactory.getLogger(CITagsProviderImpl.class);

  private final Map<String, String> ciTags;
  private final boolean ci;

  public CITagsProviderImpl(
      CIProviderInfo ciProviderInfo,
      CILocalGitInfoBuilder ciLocalGitInfoBuilder,
      UserSuppliedGitInfoBuilder userSuppliedGitInfoBuilder,
      String gitFolderName) {
    final CIInfo ciInfo = ciProviderInfo.buildCIInfo();
    final GitInfo ciGitInfo = ciProviderInfo.buildCIGitInfo();
    final GitInfo localGitInfo = ciLocalGitInfoBuilder.build(ciInfo, gitFolderName);
    final GitInfo userSuppliedGitInfo = userSuppliedGitInfoBuilder.build();

    ci = ciProviderInfo.isCI();
    ciTags =
        new CITagsBuilder()
            .withCiProviderName(ciInfo.getCiProviderName())
            .withCiPipelineId(ciInfo.getCiPipelineId())
            .withCiPipelineName(ciInfo.getCiPipelineName())
            .withCiStageName(ciInfo.getCiStageName())
            .withCiJobName(ciInfo.getCiJobName())
            .withCiPipelineNumber(ciInfo.getCiPipelineNumber())
            .withCiPipelineUrl(ciInfo.getCiPipelineUrl())
            .withCiJorUrl(ciInfo.getCiJobUrl())
            .withCiWorkspacePath(ciInfo.getCiWorkspace())
            .withCiEnvVars(ciInfo.getCiEnvVars())
            .withGitRepositoryUrl(userSuppliedGitInfo, ciGitInfo, localGitInfo)
            .withGitCommit(userSuppliedGitInfo, ciGitInfo, localGitInfo)
            .withGitBranch(userSuppliedGitInfo, ciGitInfo, localGitInfo)
            .withGitTag(userSuppliedGitInfo, ciGitInfo, localGitInfo)
            .withGitCommitAuthorName(userSuppliedGitInfo, ciGitInfo, localGitInfo)
            .withGitCommitAuthorEmail(userSuppliedGitInfo, ciGitInfo, localGitInfo)
            .withGitCommitAuthorDate(userSuppliedGitInfo, ciGitInfo, localGitInfo)
            .withGitCommitCommitterName(userSuppliedGitInfo, ciGitInfo, localGitInfo)
            .withGitCommitCommitterEmail(userSuppliedGitInfo, ciGitInfo, localGitInfo)
            .withGitCommitCommitterDate(userSuppliedGitInfo, ciGitInfo, localGitInfo)
            .withGitCommitMessage(userSuppliedGitInfo, ciGitInfo, localGitInfo)
            .build();

    if (!userSuppliedGitInfo.isEmpty()) {
      // if there is any git metadata supplied by the user, we want to check that repo URL and
      // commit SHA are valid
      String resolvedRepoUrl = ciTags.get(Tags.GIT_REPOSITORY_URL);
      if (resolvedRepoUrl == null || resolvedRepoUrl.isEmpty()) {
        log.error(
            "Could not resolve git repository URL (can be provided via "
                + GitInfo.DD_GIT_REPOSITORY_URL
                + " env var)");
      }

      String resolvedCommitSha = ciTags.get(Tags.GIT_COMMIT_SHA);
      if (!GitUtils.isValidCommitSha(resolvedCommitSha)) {
        log.error(
            "Git commit SHA could not be resolved or is invalid: "
                + resolvedCommitSha
                + " (can be provided via "
                + GitInfo.DD_GIT_COMMIT_SHA
                + " env var, and must be a full-length git SHA)");
      }
    }
  }

  @Override
  public boolean isCI() {
    return ci;
  }

  @Override
  public Map<String, String> getCiTags() {
    return ciTags;
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

    public CITagsBuilder withCiEnvVars(final Map<String, String> ciEnvVars) {
      if (ciEnvVars == null || ciEnvVars.isEmpty()) {
        return this;
      }
      return putTagValue(DDTags.CI_ENV_VARS, toJson(ciEnvVars));
    }

    public CITagsBuilder withGitRepositoryUrl(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      return putTagValue(
          Tags.GIT_REPOSITORY_URL,
          userSuppliedGitInfo.getRepositoryURL(),
          ciGitInfo.getRepositoryURL(),
          localGitInfo.getRepositoryURL());
    }

    public CITagsBuilder withGitCommit(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      return putTagValue(
          Tags.GIT_COMMIT_SHA,
          userSuppliedGitInfo.getCommit().getSha(),
          ciGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getSha());
    }

    public CITagsBuilder withGitBranch(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      return putTagValue(
          Tags.GIT_BRANCH,
          userSuppliedGitInfo.getBranch(),
          ciGitInfo.getBranch(),
          localGitInfo.getBranch());
    }

    public CITagsBuilder withGitTag(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      return putTagValue(
          Tags.GIT_TAG, userSuppliedGitInfo.getTag(), ciGitInfo.getTag(), localGitInfo.getTag());
    }

    public CITagsBuilder withGitCommitAuthorName(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      final String userSuppliedGitAuthorName =
          userSuppliedGitInfo.getCommit().getAuthor().getName();
      final String ciGitAuthorName = ciGitInfo.getCommit().getAuthor().getName();
      final String localGitAuthorName =
          isCommitShaEquals(ciGitInfo, localGitInfo)
              ? localGitInfo.getCommit().getAuthor().getName()
              : null;
      return putTagValue(
          Tags.GIT_COMMIT_AUTHOR_NAME,
          userSuppliedGitAuthorName,
          ciGitAuthorName,
          localGitAuthorName);
    }

    public CITagsBuilder withGitCommitAuthorEmail(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      final String userSuppliedGitAuthorEmail =
          userSuppliedGitInfo.getCommit().getAuthor().getEmail();
      final String ciGitAuthorEmail = ciGitInfo.getCommit().getAuthor().getEmail();
      final String localGitAuthorEmail =
          isCommitShaEquals(ciGitInfo, localGitInfo)
              ? localGitInfo.getCommit().getAuthor().getEmail()
              : null;
      return putTagValue(
          Tags.GIT_COMMIT_AUTHOR_EMAIL,
          userSuppliedGitAuthorEmail,
          ciGitAuthorEmail,
          localGitAuthorEmail);
    }

    public CITagsBuilder withGitCommitAuthorDate(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      final String userSuppliedGitAuthorDate =
          userSuppliedGitInfo.getCommit().getAuthor().getISO8601Date();
      final String ciGitAuthorDate = ciGitInfo.getCommit().getAuthor().getISO8601Date();
      final String localGitAuthorDate =
          isCommitShaEquals(ciGitInfo, localGitInfo)
              ? localGitInfo.getCommit().getAuthor().getISO8601Date()
              : null;
      return putTagValue(
          Tags.GIT_COMMIT_AUTHOR_DATE,
          userSuppliedGitAuthorDate,
          ciGitAuthorDate,
          localGitAuthorDate);
    }

    public CITagsBuilder withGitCommitCommitterName(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      final String userSuppliedGitCommitterName =
          userSuppliedGitInfo.getCommit().getCommitter().getName();
      final String ciGitCommitterName = ciGitInfo.getCommit().getCommitter().getName();
      final String localGitCommitterName =
          isCommitShaEquals(ciGitInfo, localGitInfo)
              ? localGitInfo.getCommit().getCommitter().getName()
              : null;
      return putTagValue(
          Tags.GIT_COMMIT_COMMITTER_NAME,
          userSuppliedGitCommitterName,
          ciGitCommitterName,
          localGitCommitterName);
    }

    public CITagsBuilder withGitCommitCommitterEmail(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      final String userSuppliedGitCommitterEmail =
          userSuppliedGitInfo.getCommit().getCommitter().getEmail();
      final String ciGitCommitterEmail = ciGitInfo.getCommit().getCommitter().getEmail();
      final String localCommitterEmail =
          isCommitShaEquals(ciGitInfo, localGitInfo)
              ? localGitInfo.getCommit().getCommitter().getEmail()
              : null;
      return putTagValue(
          Tags.GIT_COMMIT_COMMITTER_EMAIL,
          userSuppliedGitCommitterEmail,
          ciGitCommitterEmail,
          localCommitterEmail);
    }

    public CITagsBuilder withGitCommitCommitterDate(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      final String userSuppliedGitCommitterDate =
          userSuppliedGitInfo.getCommit().getCommitter().getISO8601Date();
      final String ciGitCommitterDate = ciGitInfo.getCommit().getCommitter().getISO8601Date();
      final String localGitCommitterDate =
          isCommitShaEquals(ciGitInfo, localGitInfo)
              ? localGitInfo.getCommit().getCommitter().getISO8601Date()
              : null;
      return putTagValue(
          Tags.GIT_COMMIT_COMMITTER_DATE,
          userSuppliedGitCommitterDate,
          ciGitCommitterDate,
          localGitCommitterDate);
    }

    public CITagsBuilder withGitCommitMessage(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      final String userSuppliedGitCommitMessage = userSuppliedGitInfo.getCommit().getFullMessage();
      final String ciGitCommitMessage = ciGitInfo.getCommit().getFullMessage();
      final String localGitCommitMessage =
          isCommitShaEquals(ciGitInfo, localGitInfo)
              ? localGitInfo.getCommit().getFullMessage()
              : null;
      return putTagValue(
          Tags.GIT_COMMIT_MESSAGE,
          userSuppliedGitCommitMessage,
          ciGitCommitMessage,
          localGitCommitMessage);
    }

    public Map<String, String> build() {
      return ciTags;
    }

    private CITagsBuilder putTagValue(
        final String tagKey, final String tagValue, final String... fallbackValues) {
      if (tagValue != null) {
        ciTags.put(tagKey, tagValue);
      } else {
        for (final String fallbackValue : fallbackValues) {
          if (fallbackValue != null) {
            ciTags.put(tagKey, fallbackValue);
            break;
          }
        }
      }
      return this;
    }

    private boolean isCommitShaEquals(GitInfo ciGitInfo, GitInfo localGitInfo) {
      final String ciGitCommit = ciGitInfo.getCommit().getSha();
      final String localFSGitCommit = localGitInfo.getCommit().getSha();
      return ciGitCommit == null || ciGitCommit.equalsIgnoreCase(localFSGitCommit);
    }
  }
}
