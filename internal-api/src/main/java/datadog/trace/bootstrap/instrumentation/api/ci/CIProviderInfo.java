package datadog.trace.bootstrap.instrumentation.api.ci;

import static datadog.trace.bootstrap.instrumentation.api.ci.AppVeyorInfo.APPVEYOR;
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE;
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET;
import static datadog.trace.bootstrap.instrumentation.api.ci.BitriseInfo.BITRISE;
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE;
import static datadog.trace.bootstrap.instrumentation.api.ci.CircleCIInfo.CIRCLECI;
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB;
import static datadog.trace.bootstrap.instrumentation.api.ci.GithubActionsInfo.GHACTIONS;
import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS;
import static datadog.trace.bootstrap.instrumentation.api.ci.TravisInfo.TRAVIS;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

public abstract class CIProviderInfo {

  protected Map<String, String> ciTags = new HashMap<>();

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
    private String ciPipelineId;
    private String ciPipelineName;
    private String ciPipelineNumber;
    private String ciPipelineUrl;
    private String ciJobUrl;
    private String ciWorkspacePath;
    private String gitRepositoryUrl;
    private String gitCommit;
    private String gitBranch;
    private String gitTag;

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
        ciTags.put(Tags._GIT_COMMIT_SHA, gitCommit);
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

    public Map<String, String> build() {
      return ciTags;
    }
  }
}
