package datadog.trace.bootstrap.instrumentation.decorator.ci;

import static datadog.trace.bootstrap.instrumentation.decorator.ci.AppVeyorInfo.APPVEYOR;
import static datadog.trace.bootstrap.instrumentation.decorator.ci.AzurePipelinesInfo.AZURE;
import static datadog.trace.bootstrap.instrumentation.decorator.ci.BitBucketInfo.BITBUCKET;
import static datadog.trace.bootstrap.instrumentation.decorator.ci.BuildkiteInfo.BUILDKITE;
import static datadog.trace.bootstrap.instrumentation.decorator.ci.CircleCIInfo.CIRCLECI;
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB;
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GithubActionsInfo.GHACTIONS;
import static datadog.trace.bootstrap.instrumentation.decorator.ci.JenkinsInfo.JENKINS;
import static datadog.trace.bootstrap.instrumentation.decorator.ci.TravisInfo.TRAVIS;

import java.net.URI;
import java.net.URISyntaxException;

public abstract class CIProviderInfo {

  public boolean isCI() {
    return true;
  }

  public abstract String getCiProviderName();

  public abstract String getCiPipelineId();

  public abstract String getCiPipelineName();

  public abstract String getCiPipelineNumber();

  public abstract String getCiPipelineUrl();

  public abstract String getCiJobUrl();

  public abstract String getCiWorkspacePath();

  public abstract String getGitRepositoryUrl();

  public abstract String getGitCommit();

  public abstract String getGitBranch();

  public abstract String getGitTag();

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
      return rawRef;
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
      return urlStr;
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
