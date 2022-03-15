package datadog.trace.bootstrap.instrumentation.ci;

import static datadog.trace.bootstrap.instrumentation.ci.AppVeyorInfo.APPVEYOR;
import static datadog.trace.bootstrap.instrumentation.ci.AzurePipelinesInfo.AZURE;
import static datadog.trace.bootstrap.instrumentation.ci.BitBucketInfo.BITBUCKET;
import static datadog.trace.bootstrap.instrumentation.ci.BitriseInfo.BITRISE;
import static datadog.trace.bootstrap.instrumentation.ci.BuildkiteInfo.BUILDKITE;
import static datadog.trace.bootstrap.instrumentation.ci.CircleCIInfo.CIRCLECI;
import static datadog.trace.bootstrap.instrumentation.ci.GitLabInfo.GITLAB;
import static datadog.trace.bootstrap.instrumentation.ci.GithubActionsInfo.GHACTIONS;
import static datadog.trace.bootstrap.instrumentation.ci.JenkinsInfo.JENKINS;
import static datadog.trace.bootstrap.instrumentation.ci.TravisInfo.TRAVIS;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_BRANCH;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_AUTHOR_DATE;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_AUTHOR_EMAIL;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_AUTHOR_NAME;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_COMMITTER_DATE;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_COMMITTER_EMAIL;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_COMMITTER_NAME;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_MESSAGE;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_SHA;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_REPOSITORY_URL;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_TAG;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.ci.git.CommitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.GitUtils;
import datadog.trace.bootstrap.instrumentation.ci.git.LocalFSGitInfoExtractor;
import datadog.trace.bootstrap.instrumentation.ci.git.PersonInfo;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@SuppressForbidden
public abstract class CIProviderInfo {

  protected Map<String, String> ciTags;

  public CIProviderInfo() {
    final CIInfo ciInfo = buildCIInfo();
    final GitInfo ciGitInfo = buildCIGitInfo();
    final GitInfo localGitInfo = buildCILocalGitInfo(ciInfo);
    final GitInfo userSuppliedGitInfo = buildCIUserSuppliedGitInfo();

    this.ciTags =
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
  }

  protected abstract GitInfo buildCIGitInfo();

  protected abstract CIInfo buildCIInfo();

  protected String getGitFolderName() {
    return ".git";
  }

  private GitInfo buildCILocalGitInfo(CIInfo ciInfo) {
    if (ciInfo.getCiWorkspace() == null) {
      return GitInfo.NOOP;
    }
    return new LocalFSGitInfoExtractor()
        .headCommit(
            Paths.get(ciInfo.getCiWorkspace(), getGitFolderName()).toFile().getAbsolutePath());
  }

  private GitInfo buildCIUserSuppliedGitInfo() {
    final String gitRepositoryUrl = System.getenv(DD_GIT_REPOSITORY_URL);

    // The user can set the DD_GIT_BRANCH manually but
    // using the value returned by the CI Provider, so
    // we need to normalize the value. Also, it can contain
    // the tag (e.g. origin/tags/0.1.0)
    String gitTag = System.getenv(DD_GIT_TAG);
    String gitBranch = null;
    final String rawGitBranchOrTag = System.getenv(DD_GIT_BRANCH);
    if (rawGitBranchOrTag != null) {
      if (!rawGitBranchOrTag.contains("tags")) {
        gitBranch = normalizeRef(rawGitBranchOrTag);
      } else if (gitTag == null) {
        gitTag = normalizeRef(rawGitBranchOrTag);
      }
    }
    final String gitCommitSha = System.getenv(DD_GIT_COMMIT_SHA);
    final String gitCommitMessage = System.getenv(DD_GIT_COMMIT_MESSAGE);
    final String gitCommitAuthorName = System.getenv(DD_GIT_COMMIT_AUTHOR_NAME);
    final String gitCommitAuthorEmail = System.getenv(DD_GIT_COMMIT_AUTHOR_EMAIL);
    final String gitCommitAuthorDate = System.getenv(DD_GIT_COMMIT_AUTHOR_DATE);
    final String gitCommitCommitterName = System.getenv(DD_GIT_COMMIT_COMMITTER_NAME);
    final String gitCommitCommitterEmail = System.getenv(DD_GIT_COMMIT_COMMITTER_EMAIL);
    final String gitCommitCommitterDate = System.getenv(DD_GIT_COMMIT_COMMITTER_DATE);

    return new GitInfo(
        gitRepositoryUrl,
        gitBranch,
        gitTag,
        new CommitInfo(
            gitCommitSha,
            new PersonInfo(gitCommitAuthorName, gitCommitAuthorEmail, gitCommitAuthorDate),
            new PersonInfo(gitCommitCommitterName, gitCommitCommitterEmail, gitCommitCommitterDate),
            gitCommitMessage));
  }

  public boolean isCI() {
    return true;
  }

  public Map<String, String> getCiTags() {
    return ciTags;
  }

  public static CIProviderInfo selectCI() {
    // CI and Git information is obtained
    // from different environment variables
    // depending on which CI server is running the build.
    if (System.getenv(JENKINS) != null) {
      return new JenkinsInfo();
    } else if (System.getenv(GITLAB) != null) {
      return new GitLabInfo();
    } else if (System.getenv(TRAVIS) != null) {
      return new TravisInfo();
    } else if (System.getenv(CIRCLECI) != null) {
      return new CircleCIInfo();
    } else if (System.getenv(APPVEYOR) != null) {
      return new AppVeyorInfo();
    } else if (System.getenv(AZURE) != null) {
      return new AzurePipelinesInfo();
    } else if (System.getenv(BITBUCKET) != null) {
      return new BitBucketInfo();
    } else if (System.getenv(GHACTIONS) != null) {
      return new GithubActionsInfo();
    } else if (System.getenv(BUILDKITE) != null) {
      return new BuildkiteInfo();
    } else if (System.getenv(BITRISE) != null) {
      return new BitriseInfo();
    } else {
      return new UnknownCIInfo();
    }
  }

  protected String expandTilde(final String path) {
    if (path == null || path.isEmpty() || !path.startsWith("~")) {
      return path;
    }

    if (!path.equals("~") && !path.startsWith("~/")) {
      // Home dir expansion is not supported for other user.
      // Returning path without modifications.
      return path;
    }

    return path.replaceFirst("^~", System.getProperty("user.home"));
  }

  protected String normalizeRef(final String rawRef) {
    return GitUtils.normalizeRef(rawRef);
  }

  protected String filterSensitiveInfo(final String urlStr) {
    return GitUtils.filterSensitiveInfo(urlStr);
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
      return this.putTagValue(Tags.CI_WORKSPACE_PATH, ciWorkspacePath);
    }

    public CITagsBuilder withGitRepositoryUrl(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      return this.putTagValue(
          Tags.GIT_REPOSITORY_URL,
          userSuppliedGitInfo.getRepositoryURL(),
          ciGitInfo.getRepositoryURL(),
          localGitInfo.getRepositoryURL());
    }

    public CITagsBuilder withGitCommit(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      return this.putTagValue(
          Tags.GIT_COMMIT_SHA,
          userSuppliedGitInfo.getCommit().getSha(),
          ciGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getSha());
    }

    public CITagsBuilder withGitBranch(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      return this.putTagValue(
          Tags.GIT_BRANCH,
          userSuppliedGitInfo.getBranch(),
          ciGitInfo.getBranch(),
          localGitInfo.getBranch());
    }

    public CITagsBuilder withGitTag(
        final GitInfo userSuppliedGitInfo, final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      return this.putTagValue(
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
      return this.putTagValue(
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
      return this.putTagValue(
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
      return this.putTagValue(
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
      return this.putTagValue(
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
      return this.putTagValue(
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
      return this.putTagValue(
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
      return this.putTagValue(
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

  public static class CIInfo {
    public static final CIInfo NOOP = new CIInfo();

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {
      private String ciProviderName;
      private String ciPipelineId;
      private String ciPipelineName;
      private String ciStageName;
      private String ciJobName;
      private String ciPipelineNumber;
      private String ciPipelineUrl;
      private String ciJobUrl;
      private String ciWorkspace;

      public Builder ciProviderName(String ciProviderName) {
        this.ciProviderName = ciProviderName;
        return this;
      }

      public Builder ciPipelineId(String ciPipelineId) {
        this.ciPipelineId = ciPipelineId;
        return this;
      }

      public Builder ciPipelineName(String ciPipelineName) {
        this.ciPipelineName = ciPipelineName;
        return this;
      }

      public Builder ciStageName(String ciStageName) {
        this.ciStageName = ciStageName;
        return this;
      }

      public Builder ciJobName(String ciJobName) {
        this.ciJobName = ciJobName;
        return this;
      }

      public Builder ciPipelineNumber(String ciPipelineNumber) {
        this.ciPipelineNumber = ciPipelineNumber;
        return this;
      }

      public Builder ciPipelineUrl(String ciPipelineUrl) {
        this.ciPipelineUrl = ciPipelineUrl;
        return this;
      }

      public Builder ciJobUrl(String ciJobUrl) {
        this.ciJobUrl = ciJobUrl;
        return this;
      }

      public Builder ciWorkspace(String ciWorkspace) {
        this.ciWorkspace = ciWorkspace;
        return this;
      }

      public CIInfo build() {
        return new CIInfo(
            ciProviderName,
            ciPipelineId,
            ciPipelineName,
            ciStageName,
            ciJobName,
            ciPipelineNumber,
            ciPipelineUrl,
            ciJobUrl,
            ciWorkspace);
      }
    }

    private final String ciProviderName;
    private final String ciPipelineId;
    private final String ciPipelineName;
    private final String ciStageName;
    private final String ciJobName;
    private final String ciPipelineNumber;
    private final String ciPipelineUrl;
    private final String ciJobUrl;
    private final String ciWorkspace;

    public CIInfo() {
      this(null, null, null, null, null, null, null, null, null);
    }

    public CIInfo(
        String ciProviderName,
        String ciPipelineId,
        String ciPipelineName,
        String ciStageName,
        String ciJobName,
        String ciPipelineNumber,
        String ciPipelineUrl,
        String ciJobUrl,
        String ciWorkspace) {
      this.ciProviderName = ciProviderName;
      this.ciPipelineId = ciPipelineId;
      this.ciPipelineName = ciPipelineName;
      this.ciStageName = ciStageName;
      this.ciJobName = ciJobName;
      this.ciPipelineNumber = ciPipelineNumber;
      this.ciPipelineUrl = ciPipelineUrl;
      this.ciJobUrl = ciJobUrl;
      this.ciWorkspace = ciWorkspace;
    }

    public String getCiProviderName() {
      return ciProviderName;
    }

    public String getCiPipelineId() {
      return ciPipelineId;
    }

    public String getCiPipelineName() {
      return ciPipelineName;
    }

    public String getCiStageName() {
      return ciStageName;
    }

    public String getCiJobName() {
      return ciJobName;
    }

    public String getCiPipelineNumber() {
      return ciPipelineNumber;
    }

    public String getCiPipelineUrl() {
      return ciPipelineUrl;
    }

    public String getCiJobUrl() {
      return ciJobUrl;
    }

    public String getCiWorkspace() {
      return ciWorkspace;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      CIInfo ciInfo = (CIInfo) o;
      return Objects.equals(ciProviderName, ciInfo.ciProviderName)
          && Objects.equals(ciPipelineId, ciInfo.ciPipelineId)
          && Objects.equals(ciPipelineName, ciInfo.ciPipelineName)
          && Objects.equals(ciStageName, ciInfo.ciStageName)
          && Objects.equals(ciJobName, ciInfo.ciJobName)
          && Objects.equals(ciPipelineNumber, ciInfo.ciPipelineNumber)
          && Objects.equals(ciPipelineUrl, ciInfo.ciPipelineUrl)
          && Objects.equals(ciJobUrl, ciInfo.ciJobUrl)
          && Objects.equals(ciWorkspace, ciInfo.ciWorkspace);
    }

    @Override
    public int hashCode() {
      int hash = 1;
      hash = 31 * hash + (ciProviderName == null ? 0 : ciProviderName.hashCode());
      hash = 31 * hash + (ciPipelineId == null ? 0 : ciPipelineId.hashCode());
      hash = 31 * hash + (ciPipelineName == null ? 0 : ciPipelineName.hashCode());
      hash = 31 * hash + (ciStageName == null ? 0 : ciStageName.hashCode());
      hash = 31 * hash + (ciJobName == null ? 0 : ciJobName.hashCode());
      hash = 31 * hash + (ciPipelineNumber == null ? 0 : ciPipelineNumber.hashCode());
      hash = 31 * hash + (ciPipelineUrl == null ? 0 : ciPipelineUrl.hashCode());
      hash = 31 * hash + (ciJobUrl == null ? 0 : ciJobUrl.hashCode());
      hash = 31 * hash + (ciWorkspace == null ? 0 : ciWorkspace.hashCode());
      return hash;
    }

    @Override
    public String toString() {
      return "CIInfo{"
          + "ciProviderName='"
          + ciProviderName
          + '\''
          + ", ciPipelineId='"
          + ciPipelineId
          + '\''
          + ", ciPipelineName='"
          + ciPipelineName
          + '\''
          + ", ciStageName='"
          + ciStageName
          + '\''
          + ", ciJobName='"
          + ciJobName
          + '\''
          + ", ciPipelineNumber='"
          + ciPipelineNumber
          + '\''
          + ", ciPipelineUrl='"
          + ciPipelineUrl
          + '\''
          + ", ciJobUrl='"
          + ciJobUrl
          + '\''
          + ", ciWorkspace='"
          + ciWorkspace
          + '\''
          + '}';
    }
  }
}
