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
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfoExtractor;
import datadog.trace.bootstrap.instrumentation.ci.git.GitUtils;
import datadog.trace.bootstrap.instrumentation.ci.git.LocalFSGitInfoExtractor;
import de.thetaphi.forbiddenapis.SuppressForbidden;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@SuppressForbidden
public abstract class CIProviderInfo {

  protected Map<String, String> ciTags = new HashMap<>();
  protected GitInfoExtractor localFSGitInfoExtractor = new LocalFSGitInfoExtractor();

  private final String workspace;
  private final GitInfo localGitInfo;

  public CIProviderInfo() {
    this.workspace = expandTilde(buildWorkspace());
    this.localGitInfo =
      this.localFSGitInfoExtractor.headCommit(
        Paths.get(this.workspace, getGitFolderName()).toFile().getAbsolutePath());
  }

  protected String getGitFolderName() {
    return ".git";
  }

  protected abstract String buildWorkspace();

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
      final String gitCommit,
      final String localFSGitCommit,
      final String localFSGitCommitAuthorName) {
      return this.putTagValueIfCommitEquals(
        Tags.GIT_COMMIT_AUTHOR_NAME, gitCommit, localFSGitCommit, localFSGitCommitAuthorName);
    }

    public CITagsBuilder withGitCommitAuthorEmail(
      final String gitCommit,
      final String localFSGitCommit,
      final String localFSGitCommitAuthorEmail) {
      return this.putTagValueIfCommitEquals(
        Tags.GIT_COMMIT_AUTHOR_EMAIL, gitCommit, localFSGitCommit, localFSGitCommitAuthorEmail);
    }

    public CITagsBuilder withGitCommitAuthorDate(
      final String gitCommit,
      final String localFSGitCommit,
      final String localFSGitCommitAuthorDate) {
      return this.putTagValueIfCommitEquals(
        Tags.GIT_COMMIT_AUTHOR_DATE, gitCommit, localFSGitCommit, localFSGitCommitAuthorDate);
    }

    public CITagsBuilder withGitCommitCommitterName(
      final String gitCommit,
      final String localFSGitCommit,
      final String localFSGitCommitCommitterName) {
      return this.putTagValueIfCommitEquals(
        Tags.GIT_COMMIT_COMMITTER_NAME,
        gitCommit,
        localFSGitCommit,
        localFSGitCommitCommitterName);
    }

    public CITagsBuilder withGitCommitCommitterEmail(
      final String gitCommit,
      final String localFSGitCommit,
      final String localFSGitCommitCommitterEmail) {
      return this.putTagValueIfCommitEquals(
        Tags.GIT_COMMIT_COMMITTER_EMAIL,
        gitCommit,
        localFSGitCommit,
        localFSGitCommitCommitterEmail);
    }

    public CITagsBuilder withGitCommitCommitterDate(
      final String gitCommit,
      final String localFSGitCommit,
      final String localFSGitCommitCommitterDate) {
      return this.putTagValueIfCommitEquals(
        Tags.GIT_COMMIT_COMMITTER_DATE,
        gitCommit,
        localFSGitCommit,
        localFSGitCommitCommitterDate);
    }

    public CITagsBuilder withGitCommitMessage(
      final String gitCommit,
      final String localFSGitCommit,
      final String localFSGitCommitMessage) {
      return this.putTagValueIfCommitEquals(
        Tags.GIT_COMMIT_MESSAGE, gitCommit, localFSGitCommit, localFSGitCommitMessage);
    }

    public Map<String, String> build() {
      return ciTags;
    }

    private CITagsBuilder putTagValue(final String tagKey, final String tagValue) {
      return this.putTagValue(tagKey, tagValue, null);
    }

    private CITagsBuilder putTagValue(
      final String tagKey, final String tagValue, final String fallbackValue) {
      if (tagKey == null) {
        return this;
      }

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
      if (tagKey == null) {
        return this;
      }

      // As we're calculating the commit from localFS, we want to ensure that
      // ciGitCommit is equals to localFSGitCommit before we put the information in the tag map.
      if (ciGitCommit == null || ciGitCommit.equalsIgnoreCase(localFSGitCommit)) {
        this.putTagValue(tagKey, tagValue);
      }
      return this;
    }
  }

  public String getWorkspace() {
    return this.workspace;
  }

  public GitInfo getLocalGitInfo() {
    return this.localGitInfo;
  }

  public String getLocalGitRepositoryUrl() {
    return this.getLocalGitInfo().getRepositoryURL();
  }

  public String getLocalGitBranch() {
    return this.getLocalGitInfo().getBranch();
  }

  public String getLocalGitTag() {
    return this.getLocalGitInfo().getTag();
  }

  public String getLocalGitCommitSha() {
    return this.getLocalGitInfo().getCommit().getSha();
  }

  public String getLocalGitCommitAuthorName() {
    return this.getLocalGitInfo().getCommit().getAuthor().getName();
  }

  public String getLocalGitCommitAuthorEmail() {
    return this.getLocalGitInfo().getCommit().getAuthor().getEmail();
  }

  public String getLocalGitCommitAuthorDate() {
    return this.getLocalGitInfo().getCommit().getAuthor().getISO8601Date();
  }

  public String getLocalGitCommitCommitterName() {
    return this.getLocalGitInfo().getCommit().getCommitter().getName();
  }

  public String getLocalGitCommitCommitterEmail() {
    return this.getLocalGitInfo().getCommit().getCommitter().getEmail();
  }

  public String getLocalGitCommitCommitterDate() {
    return this.getLocalGitInfo().getCommit().getCommitter().getISO8601Date();
  }

  public String getLocalGitCommitMessage() {
    return this.getLocalGitInfo().getCommit().getFullMessage();
  }
}
