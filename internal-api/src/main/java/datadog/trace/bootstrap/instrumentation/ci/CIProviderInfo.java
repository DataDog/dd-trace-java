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

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.GitUtils;
import datadog.trace.bootstrap.instrumentation.ci.git.LocalFSGitInfoExtractor;
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
    final String ciGitCommit = ciGitInfo.getCommit().getSha();
    final String localFSGitCommit = localGitInfo.getCommit().getSha();

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
            .withGitRepositoryUrl(ciGitInfo.getRepositoryURL(), localGitInfo.getRepositoryURL())
            .withGitCommit(ciGitCommit, localFSGitCommit)
            .withGitBranch(ciGitInfo.getBranch(), localGitInfo.getBranch())
            .withGitTag(ciGitInfo.getTag(), localGitInfo.getTag())
            .withGitCommitAuthorName(ciGitInfo, localGitInfo)
            .withGitCommitAuthorEmail(ciGitInfo, localGitInfo)
            .withGitCommitAuthorDate(ciGitInfo, localGitInfo)
            .withGitCommitCommitterName(ciGitInfo, localGitInfo)
            .withGitCommitCommitterEmail(ciGitInfo, localGitInfo)
            .withGitCommitCommitterDate(ciGitInfo, localGitInfo)
            .withGitCommitMessage(ciGitInfo, localGitInfo)
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
      return new NoopCIInfo();
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
        final String gitRepositoryUrl, final String localFSGitRepositoryUrl) {
      return this.putTagValue(Tags.GIT_REPOSITORY_URL, gitRepositoryUrl, localFSGitRepositoryUrl);
    }

    public CITagsBuilder withGitCommit(final String gitCommit, final String localFSGitCommit) {
      return this.putTagValue(Tags.GIT_COMMIT_SHA, gitCommit, localFSGitCommit);
    }

    public CITagsBuilder withGitBranch(final String gitBranch, final String localFSGitBranch) {
      return this.putTagValue(Tags.GIT_BRANCH, gitBranch, localFSGitBranch);
    }

    public CITagsBuilder withGitTag(final String gitTag, final String localFSGitTag) {
      return this.putTagValue(Tags.GIT_TAG, gitTag, localFSGitTag);
    }

    public CITagsBuilder withGitCommitAuthorName(
        final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      if (ciGitInfo.getCommit().getAuthor().getName() != null) {
        this.putTagValue(Tags.GIT_COMMIT_AUTHOR_NAME, ciGitInfo.getCommit().getAuthor().getName());
        return this;
      }

      return this.putTagValueIfCommitEquals(
          Tags.GIT_COMMIT_AUTHOR_NAME,
          ciGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getAuthor().getName());
    }

    public CITagsBuilder withGitCommitAuthorEmail(
        final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      if (ciGitInfo.getCommit().getAuthor().getEmail() != null) {
        this.putTagValue(
            Tags.GIT_COMMIT_AUTHOR_EMAIL, ciGitInfo.getCommit().getAuthor().getEmail());
        return this;
      }

      return this.putTagValueIfCommitEquals(
          Tags.GIT_COMMIT_AUTHOR_EMAIL,
          ciGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getAuthor().getEmail());
    }

    public CITagsBuilder withGitCommitAuthorDate(
        final GitInfo ciGitInfo, final GitInfo localGitInfo) {
      if (ciGitInfo.getCommit().getAuthor().getWhen() > 0) {
        this.putTagValue(
            Tags.GIT_COMMIT_AUTHOR_DATE, ciGitInfo.getCommit().getAuthor().getISO8601Date());
        return this;
      }

      return this.putTagValueIfCommitEquals(
          Tags.GIT_COMMIT_AUTHOR_DATE,
          ciGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getAuthor().getISO8601Date());
    }

    public CITagsBuilder withGitCommitCommitterName(
        final GitInfo ciGitInfo, final GitInfo localGitInfo) {

      if (ciGitInfo.getCommit().getCommitter().getName() != null) {
        this.putTagValue(
            Tags.GIT_COMMIT_COMMITTER_NAME, ciGitInfo.getCommit().getCommitter().getName());
        return this;
      }

      return this.putTagValueIfCommitEquals(
          Tags.GIT_COMMIT_COMMITTER_NAME,
          ciGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getCommitter().getName());
    }

    public CITagsBuilder withGitCommitCommitterEmail(
        final GitInfo ciGitInfo, final GitInfo localGitInfo) {

      if (ciGitInfo.getCommit().getCommitter().getEmail() != null) {
        this.putTagValue(
            Tags.GIT_COMMIT_COMMITTER_EMAIL, ciGitInfo.getCommit().getCommitter().getEmail());
        return this;
      }

      return this.putTagValueIfCommitEquals(
          Tags.GIT_COMMIT_COMMITTER_EMAIL,
          ciGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getCommitter().getEmail());
    }

    public CITagsBuilder withGitCommitCommitterDate(
        final GitInfo ciGitInfo, final GitInfo localGitInfo) {

      if (ciGitInfo.getCommit().getCommitter().getWhen() > 0) {
        this.putTagValue(
            Tags.GIT_COMMIT_COMMITTER_DATE, ciGitInfo.getCommit().getCommitter().getISO8601Date());
        return this;
      }

      return this.putTagValueIfCommitEquals(
          Tags.GIT_COMMIT_COMMITTER_DATE,
          ciGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getCommitter().getISO8601Date());
    }

    public CITagsBuilder withGitCommitMessage(final GitInfo ciGitInfo, final GitInfo localGitInfo) {

      if (ciGitInfo.getCommit().getFullMessage() != null) {
        this.putTagValue(Tags.GIT_COMMIT_MESSAGE, ciGitInfo.getCommit().getFullMessage());
        return this;
      }

      return this.putTagValueIfCommitEquals(
          Tags.GIT_COMMIT_MESSAGE,
          ciGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getSha(),
          localGitInfo.getCommit().getFullMessage());
    }

    public Map<String, String> build() {
      return ciTags;
    }

    private CITagsBuilder putTagValue(final String tagKey, final String tagValue) {
      return this.putTagValue(tagKey, tagValue, null);
    }

    private CITagsBuilder putTagValue(
        final String tagKey, final String tagValue, final String fallbackValue) {
      if (tagValue != null) {
        ciTags.put(tagKey, tagValue);
      } else if (fallbackValue != null) {
        ciTags.put(tagKey, fallbackValue);
      }
      return this;
    }

    private CITagsBuilder putTagValueIfCommitEquals(
        final String tagKey,
        final String ciGitCommit,
        final String localFSGitCommit,
        final String tagValue) {
      // As we're calculating the commit from localFS, we want to ensure that
      // ciGitCommit is equals to localFSGitCommit before we put the information in the tag map.
      if (ciGitCommit == null || ciGitCommit.equalsIgnoreCase(localFSGitCommit)) {
        this.putTagValue(tagKey, tagValue);
      }
      return this;
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
      return Objects.hash(
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
