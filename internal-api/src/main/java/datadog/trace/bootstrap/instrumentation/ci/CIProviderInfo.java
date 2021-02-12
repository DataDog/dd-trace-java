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
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfoExtractor;
import datadog.trace.bootstrap.instrumentation.ci.git.LocalFSGitInfoExtractor;
import de.thetaphi.forbiddenapis.SuppressForbidden;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

@SuppressForbidden
public abstract class CIProviderInfo {

  protected Map<String, String> ciTags = new HashMap<>();
  protected GitInfoExtractor gitInfoExtractor = new LocalFSGitInfoExtractor();

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
    if (rawRef == null || rawRef.isEmpty()) {
      return null;
    }

    String ref = rawRef;
    if (ref.startsWith("origin")) {
      ref = ref.replace("origin/", "");
    } else if (ref.startsWith("refs/heads")) {
      ref = ref.replace("refs/heads/", "");
    }

    if (ref.startsWith("tags")) {
      return ref.replace("tags/", "");
    }

    return ref;
  }

  protected String filterSensitiveInfo(final String urlStr) {
    if (urlStr == null || urlStr.isEmpty()) {
      return null;
    }

    try {
      final URI url = new URI(urlStr);
      final String userInfo = url.getRawUserInfo();
      return urlStr.replace(userInfo + "@", "");
    } catch (final URISyntaxException ex) {
      return urlStr;
    }
  }

  public static class CITagsBuilder {

    private final Map<String, String> ciTags = new HashMap<>();

    public CITagsBuilder withCiProviderName(final String ciProviderName) {
      if (ciProviderName != null) {
        ciTags.put(Tags.CI_PROVIDER_NAME, ciProviderName);
      }
      return this;
    }

    public CITagsBuilder withCiPipelineId(final String ciPipelineId) {
      if (ciPipelineId != null) {
        ciTags.put(Tags.CI_PIPELINE_ID, ciPipelineId);
      }
      return this;
    }

    public CITagsBuilder withCiPipelineName(final String ciPipelineName) {
      if (ciPipelineName != null) {
        ciTags.put(Tags.CI_PIPELINE_NAME, ciPipelineName);
      }
      return this;
    }

    public CITagsBuilder withCiPipelineNumber(final String ciPipelineNumber) {
      if (ciPipelineNumber != null) {
        ciTags.put(Tags.CI_PIPELINE_NUMBER, ciPipelineNumber);
      }
      return this;
    }

    public CITagsBuilder withCiPipelineUrl(final String ciPipelineUrl) {
      if (ciPipelineUrl != null) {
        ciTags.put(Tags.CI_PIPELINE_URL, ciPipelineUrl);
      }
      return this;
    }

    public CITagsBuilder withCiStageName(final String ciStageName) {
      if (ciStageName != null) {
        ciTags.put(Tags.CI_STAGE_NAME, ciStageName);
      }
      return this;
    }

    public CITagsBuilder withCiJobName(final String ciJobName) {
      if (ciJobName != null) {
        ciTags.put(Tags.CI_JOB_NAME, ciJobName);
      }
      return this;
    }

    public CITagsBuilder withCiJorUrl(final String ciJobUrl) {
      if (ciJobUrl != null) {
        ciTags.put(Tags.CI_JOB_URL, ciJobUrl);
      }
      return this;
    }

    public CITagsBuilder withCiWorkspacePath(final String ciWorkspacePath) {
      if (ciWorkspacePath != null) {
        ciTags.put(Tags.CI_WORKSPACE_PATH, ciWorkspacePath);
      }
      return this;
    }

    public CITagsBuilder withGitRepositoryUrl(final String gitRepositoryUrl) {
      if (gitRepositoryUrl != null) {
        ciTags.put(Tags.GIT_REPOSITORY_URL, gitRepositoryUrl);
      }
      return this;
    }

    public CITagsBuilder withGitCommit(final String gitCommit) {
      if (gitCommit != null) {
        ciTags.put(Tags.GIT_COMMIT_SHA, gitCommit);
      }
      return this;
    }

    public CITagsBuilder withGitBranch(final String gitBranch) {
      if (gitBranch != null) {
        ciTags.put(Tags.GIT_BRANCH, gitBranch);
      }
      return this;
    }

    public CITagsBuilder withGitTag(final String gitTag) {
      if (gitTag != null) {
        ciTags.put(Tags.GIT_TAG, gitTag);
      }
      return this;
    }

    public CITagsBuilder withGitCommitAuthorName(final String gitCommitAuthorName) {
      if (gitCommitAuthorName != null) {
        ciTags.put(Tags.GIT_COMMIT_AUTHOR_NAME, gitCommitAuthorName);
      }
      return this;
    }

    public CITagsBuilder withGitCommitAuthorEmail(final String gitCommitAuthorEmail) {
      if (gitCommitAuthorEmail != null) {
        ciTags.put(Tags.GIT_COMMIT_AUTHOR_EMAIL, gitCommitAuthorEmail);
      }
      return this;
    }

    public CITagsBuilder withGitCommitAuthorDate(final String gitCommitAuthorDate) {
      if (gitCommitAuthorDate != null) {
        ciTags.put(Tags.GIT_COMMIT_AUTHOR_DATE, gitCommitAuthorDate);
      }
      return this;
    }

    public CITagsBuilder withGitCommitCommitterName(final String gitCommitCommitterName) {
      if (gitCommitCommitterName != null) {
        ciTags.put(Tags.GIT_COMMIT_COMMITTER_NAME, gitCommitCommitterName);
      }
      return this;
    }

    public CITagsBuilder withGitCommitCommitterEmail(final String gitCommitCommitterEmail) {
      if (gitCommitCommitterEmail != null) {
        ciTags.put(Tags.GIT_COMMIT_COMMITTER_EMAIL, gitCommitCommitterEmail);
      }
      return this;
    }

    public CITagsBuilder withGitCommitCommitterDate(final String gitCommitCommitterDate) {
      if (gitCommitCommitterDate != null) {
        ciTags.put(Tags.GIT_COMMIT_COMMITTER_DATE, gitCommitCommitterDate);
      }
      return this;
    }

    public CITagsBuilder withGitCommitMessage(final String gitCommitMessage) {
      if (gitCommitMessage != null) {
        ciTags.put(Tags.GIT_COMMIT_MESSAGE, gitCommitMessage);
      }
      return this;
    }

    public Map<String, String> build() {
      return ciTags;
    }
  }
}
