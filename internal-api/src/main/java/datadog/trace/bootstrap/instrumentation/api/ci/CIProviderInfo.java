package datadog.trace.bootstrap.instrumentation.api.ci;

import static datadog.trace.bootstrap.instrumentation.api.ci.AppVeyorInfo.APPVEYOR;
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE;
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET;
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

  protected final Map<String, String> ciTags = new HashMap<>();
  protected String ciProviderName;
  protected String ciPipelineId;
  protected String ciPipelineName;
  protected String ciPipelineNumber;
  protected String ciPipelineUrl;
  protected String ciJobUrl;
  protected String ciWorkspacePath;
  protected String gitRepositoryUrl;
  protected String gitCommit;
  protected String gitBranch;
  protected String gitTag;

  public boolean isCI() {
    return true;
  }

  public Map<String, String> getCiTags() {
    return ciTags;
  }

  protected void updateCiTags() {
    if (ciProviderName != null) {
      ciTags.put(Tags.CI_PROVIDER_NAME, ciProviderName);
    }

    if (ciPipelineId != null) {
      ciTags.put(Tags.CI_PIPELINE_ID, ciPipelineId);
    }

    if (ciPipelineName != null) {
      ciTags.put(Tags.CI_PIPELINE_NAME, ciPipelineName);
    }

    if (ciPipelineNumber != null) {
      ciTags.put(Tags.CI_PIPELINE_NUMBER, ciPipelineNumber);
    }

    if (ciPipelineUrl != null) {
      ciTags.put(Tags.CI_PIPELINE_URL, ciPipelineUrl);
    }

    if (ciJobUrl != null) {
      ciTags.put(Tags.CI_JOB_URL, ciJobUrl);
    }

    if (ciWorkspacePath != null) {
      ciTags.put(Tags.CI_WORKSPACE_PATH, ciWorkspacePath);
      // ciTags.put(Tags.BUILD_SOURCE_ROOT, ciWorkspacePath);
    }

    if (gitRepositoryUrl != null) {
      ciTags.put(Tags.GIT_REPOSITORY_URL, gitRepositoryUrl);
    }

    if (gitCommit != null) {
      ciTags.put(Tags.GIT_COMMIT_SHA, gitCommit);
      ciTags.put(Tags._GIT_COMMIT_SHA, gitCommit);
    }

    if (gitBranch != null) {
      ciTags.put(Tags.GIT_BRANCH, gitBranch);
    }

    if (gitTag != null) {
      ciTags.put(Tags.GIT_TAG, gitTag);
    }
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
}
