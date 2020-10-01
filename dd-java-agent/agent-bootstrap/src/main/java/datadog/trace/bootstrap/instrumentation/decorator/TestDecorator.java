package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class TestDecorator extends BaseDecorator {

  public static final String TEST_TYPE = "test";
  public static final String TEST_PASS = "pass";
  public static final String TEST_FAIL = "fail";
  public static final String TEST_SKIP = "skip";

  // https://wiki.jenkins.io/display/JENKINS/Building+a+software+project
  public static final String JENKINS = "JENKINS_URL";
  static final String JENKINS_PROVIDER_NAME = "jenkins";
  static final String JENKINS_PIPELINE_ID = "BUILD_TAG";
  static final String JENKINS_PIPELINE_NUMBER = "BUILD_NUMBER";
  static final String JENKINS_PIPELINE_URL = "BUILD_URL";
  static final String JENKINS_PIPELINE_NAME = "JOB_NAME";
  static final String JENKINS_JOB_URL = "JOB_URL";
  static final String JENKINS_WORKSPACE_PATH = "WORKSPACE";
  static final String JENKINS_GIT_REPOSITORY_URL = "GIT_URL";
  static final String JENKINS_GIT_COMMIT = "GIT_COMMIT";
  static final String JENKINS_GIT_BRANCH = "GIT_BRANCH";

  // https://docs.gitlab.com/ee/ci/variables/predefined_variables.html
  public static final String GITLAB = "GITLAB_CI";
  static final String GITLAB_PROVIDER_NAME = "gitlab";
  static final String GITLAB_PIPELINE_ID = "CI_PIPELINE_ID";
  static final String GITLAB_PIPELINE_NAME = "CI_PROJECT_PATH";
  static final String GITLAB_PIPELINE_NUMBER = "CI_PIPELINE_IID";
  static final String GITLAB_PIPELINE_URL = "CI_PIPELINE_URL";
  static final String GITLAB_JOB_URL = "CI_JOB_URL";
  static final String GITLAB_WORKSPACE_PATH = "CI_PROJECT_DIR";
  static final String GITLAB_GIT_REPOSITORY_URL = "CI_REPOSITORY_URL";
  static final String GITLAB_GIT_COMMIT = "CI_COMMIT_SHA";
  static final String GITLAB_GIT_BRANCH = "CI_COMMIT_BRANCH";
  static final String GITLAB_GIT_TAG = "CI_COMMIT_TAG";

  // https://docs.travis-ci.com/user/environment-variables/#default-environment-variables
  public static final String TRAVIS = "TRAVIS";
  static final String TRAVIS_PROVIDER_NAME = "travisci";
  static final String TRAVIS_PIPELINE_ID = "TRAVIS_BUILD_ID";
  static final String TRAVIS_PIPELINE_NUMBER = "TRAVIS_BUILD_NUMBER";
  static final String TRAVIS_PIPELINE_URL = "TRAVIS_BUILD_WEB_URL";
  static final String TRAVIS_JOB_URL = "TRAVIS_JOB_WEB_URL";
  static final String TRAVIS_WORKSPACE_PATH = "TRAVIS_BUILD_DIR";
  static final String TRAVIS_REPOSITORY_SLUG = "TRAVIS_REPO_SLUG";
  static final String TRAVIS_PR_REPOSITORY_SLUG = "TRAVIS_PULL_REQUEST_SLUG";
  static final String TRAVIS_GIT_COMMIT = "TRAVIS_COMMIT";
  static final String TRAVIS_GIT_PR_BRANCH = "TRAVIS_PULL_REQUEST_BRANCH";
  static final String TRAVIS_GIT_BRANCH = "TRAVIS_BRANCH";
  static final String TRAVIS_GIT_TAG = "TRAVIS_TAG";

  // https://circleci.com/docs/2.0/env-vars/#built-in-environment-variables
  public static final String CIRCLECI = "CIRCLECI";
  static final String CIRCLECI_PROVIDER_NAME = "circleci";
  static final String CIRCLECI_PIPELINE_ID = "CIRCLE_WORKFLOW_ID";
  static final String CIRCLECI_PIPELINE_NAME = "CIRCLE_PROJECT_REPONAME";
  static final String CIRCLECI_PIPELINE_NUMBER = "CIRCLE_BUILD_NUM";
  static final String CIRCLECI_BUILD_URL = "CIRCLE_BUILD_URL";
  static final String CIRCLECI_WORKSPACE_PATH = "CIRCLE_WORKING_DIRECTORY";
  static final String CIRCLECI_GIT_REPOSITORY_URL = "CIRCLE_REPOSITORY_URL";
  static final String CIRCLECI_GIT_COMMIT = "CIRCLE_SHA1";
  static final String CIRCLECI_GIT_BRANCH = "CIRCLE_BRANCH";
  static final String CIRCLECI_GIT_TAG = "CIRCLE_TAG";

  // https://www.appveyor.com/docs/environment-variables/
  public static final String APPVEYOR = "APPVEYOR";
  static final String APPVEYOR_PROVIDER_NAME = "appveyor";
  static final String APPVEYOR_BUILD_ID = "APPVEYOR_BUILD_ID";
  static final String APPVEYOR_REPO_NAME = "APPVEYOR_REPO_NAME";
  static final String APPVEYOR_PIPELINE_NUMBER = "APPVEYOR_BUILD_NUMBER";
  static final String APPVEYOR_WORKSPACE_PATH = "APPVEYOR_BUILD_FOLDER";
  static final String APPVEYOR_REPO_PROVIDER = "APPVEYOR_REPO_PROVIDER";
  static final String APPVEYOR_REPO_COMMIT = "APPVEYOR_REPO_COMMIT";
  static final String APPVEYOR_REPO_BRANCH = "APPVEYOR_REPO_BRANCH";
  static final String APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH =
      "APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH";
  static final String APPVEYOR_REPO_TAG_NAME = "APPVEYOR_REPO_TAG_NAME";

  // https://docs.microsoft.com/en-us/azure/devops/pipelines/build/variables?view=azure-devops
  public static final String AZURE = "TF_BUILD";
  static final String AZURE_PROVIDER_NAME = "azurepipelines";
  static final String AZURE_PIPELINE_NAME = "BUILD_DEFINITIONNAME";
  static final String AZURE_PIPELINE_NUMBER = "BUILD_BUILDNUMBER";
  static final String AZURE_SYSTEM_TEAMFOUNDATIONSERVERURI = "SYSTEM_TEAMFOUNDATIONSERVERURI";
  static final String AZURE_SYSTEM_TEAMPROJECT = "SYSTEM_TEAMPROJECT";
  static final String AZURE_BUILD_BUILDID = "BUILD_BUILDID";
  static final String AZURE_SYSTEM_JOBID = "SYSTEM_JOBID";
  static final String AZURE_SYSTEM_TASKINSTANCEID = "SYSTEM_TASKINSTANCEID";
  static final String AZURE_WORKSPACE_PATH = "BUILD_SOURCESDIRECTORY";
  static final String AZURE_SYSTEM_PULLREQUEST_SOURCEREPOSITORYURI =
      "SYSTEM_PULLREQUEST_SOURCEREPOSITORYURI";
  static final String AZURE_BUILD_REPOSITORY_URI = "BUILD_REPOSITORY_URI";
  static final String AZURE_SYSTEM_PULLREQUEST_SOURCECOMMITID = "SYSTEM_PULLREQUEST_SOURCECOMMITID";
  static final String AZURE_BUILD_SOURCEVERSION = "BUILD_SOURCEVERSION";
  static final String AZURE_SYSTEM_PULLREQUEST_SOURCEBRANCH = "SYSTEM_PULLREQUEST_SOURCEBRANCH";
  static final String AZURE_BUILD_SOURCEBRANCH = "BUILD_SOURCEBRANCH";

  // https://support.atlassian.com/bitbucket-cloud/docs/variables-and-secrets/
  public static final String BITBUCKET = "BITBUCKET_COMMIT";
  static final String BITBUCKET_PROVIDER_NAME = "bitbucket";
  static final String BITBUCKET_PIPELINE_ID = "BITBUCKET_PIPELINE_UUID";
  static final String BITBUCKET_REPO_FULL_NAME = "BITBUCKET_REPO_FULL_NAME";
  static final String BITBUCKET_BUILD_NUMBER = "BITBUCKET_BUILD_NUMBER";
  static final String BITBUCKET_WORKSPACE_PATH = "BITBUCKET_CLONE_DIR";
  static final String BITBUCKET_GIT_REPOSITORY_URL = "BITBUCKET_GIT_SSH_ORIGIN";
  static final String BITBUCKET_GIT_COMMIT = "BITBUCKET_COMMIT";
  static final String BITBUCKET_GIT_BRANCH = "BITBUCKET_BRANCH";
  static final String BITBUCKET_GIT_TAG = "BITBUCKET_TAG";

  // https://docs.github.com/en/free-pro-team@latest/actions/reference/environment-variables#default-environment-variables
  public static final String GHACTIONS = "GITHUB_ACTION";
  static final String GHACTIONS_PROVIDER_NAME = "github";
  static final String GHACTIONS_PIPELINE_ID = "GITHUB_RUN_ID";
  static final String GHACTIONS_PIPELINE_NAME = "GITHUB_WORKFLOW";
  static final String GHACTIONS_PIPELINE_NUMBER = "GITHUB_RUN_NUMBER";
  static final String GHACTIONS_WORKSPACE_PATH = "GITHUB_WORKSPACE";
  static final String GHACTIONS_REPOSITORY = "GITHUB_REPOSITORY";
  static final String GHACTIONS_SHA = "GITHUB_SHA";
  static final String GHACTIONS_HEAD_REF = "GITHUB_HEAD_REF";
  static final String GHACTIONS_REF = "GITHUB_REF";

  // https://buildkite.com/docs/pipelines/environment-variables
  public static final String BUILDKITE = "BUILDKITE";
  static final String BUILDKITE_PROVIDER_NAME = "buildkite";
  static final String BUILDKITE_PIPELINE_ID = "BUILDKITE_BUILD_ID";
  static final String BUILDKITE_PIPELINE_NAME = "BUILDKITE_PIPELINE_SLUG";
  static final String BUILDKITE_PIPELINE_NUMBER = "BUILDKITE_BUILD_NUMBER";
  static final String BUILDKITE_BUILD_URL = "BUILDKITE_BUILD_URL";
  static final String BUILDKITE_JOB_ID = "BUILDKITE_JOB_ID";
  static final String BUILDKITE_WORKSPACE_PATH = "BUILDKITE_BUILD_CHECKOUT_PATH";
  static final String BUILDKITE_GIT_REPOSITORY_URL = "BUILDKITE_REPO";
  static final String BUILDKITE_GIT_COMMIT = "BUILDKITE_COMMIT";
  static final String BUILDKITE_GIT_BRANCH = "BUILDKITE_BRANCH";
  static final String BUILDKITE_GIT_TAG = "BUILDKITE_TAG";

  @Getter private boolean isCI;
  @Getter private String ciProviderName;
  @Getter private String ciPipelineId;
  @Getter private String ciPipelineName;
  @Getter private String ciPipelineNumber;
  @Getter private String ciPipelineUrl;
  @Getter private String ciJobUrl;
  @Getter private String ciWorkspacePath;
  @Getter private String gitRepositoryUrl;
  @Getter private String gitCommit;
  @Getter private String gitBranch;
  @Getter private String gitTag;

  public TestDecorator() {
    // CI and Git information is obtained
    // from different environment variables
    // depending on which CI server is running the build.
    if (System.getenv(JENKINS) != null) {
      setJenkinsData();
    } else if (System.getenv(GITLAB) != null) {
      setGitLabData();
    } else if (System.getenv(TRAVIS) != null) {
      setTravisData();
    } else if (System.getenv(CIRCLECI) != null) {
      setCircleCIData();
    } else if (System.getenv(APPVEYOR) != null) {
      setAppveyorData();
    } else if (System.getenv(AZURE) != null) {
      setAzureData();
    } else if (System.getenv(BITBUCKET) != null) {
      setBitBucketData();
    } else if (System.getenv(GHACTIONS) != null) {
      setGHActionsData();
    } else if (System.getenv(BUILDKITE) != null) {
      setBuildkiteData();
    }
  }

  protected abstract String testFramework();

  protected String testType() {
    return TEST_TYPE;
  }

  protected String spanKind() {
    return Tags.SPAN_KIND_TEST;
  }

  @Override
  protected CharSequence spanType() {
    return DDSpanTypes.TEST;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    assert span != null;
    span.setTag(Tags.SPAN_KIND, spanKind());
    span.setTag(Tags.TEST_FRAMEWORK, testFramework());
    span.setTag(Tags.TEST_TYPE, testType());
    span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);

    span.setTag(Tags.CI_PROVIDER_NAME, ciProviderName);
    span.setTag(Tags.CI_PIPELINE_ID, ciPipelineId);
    span.setTag(Tags.CI_PIPELINE_NAME, ciPipelineName);
    span.setTag(Tags.CI_PIPELINE_NUMBER, ciPipelineNumber);
    span.setTag(Tags.CI_PIPELINE_URL, ciPipelineUrl);
    span.setTag(Tags.CI_JOB_URL, ciJobUrl);
    span.setTag(Tags.CI_WORKSPACE_PATH, ciWorkspacePath);
    span.setTag(Tags.BUILD_SOURCE_ROOT, ciWorkspacePath);

    span.setTag(Tags.GIT_REPOSITORY_URL, gitRepositoryUrl);
    span.setTag(Tags.GIT_COMMIT_SHA, gitCommit);
    span.setTag(Tags.GIT_BRANCH, gitBranch);
    span.setTag(Tags.GIT_TAG, gitTag);

    return super.afterStart(span);
  }

  private void setJenkinsData() {
    isCI = true;
    ciProviderName = JENKINS_PROVIDER_NAME;
    ciPipelineId = System.getenv(JENKINS_PIPELINE_ID);
    ciPipelineNumber = System.getenv(JENKINS_PIPELINE_NUMBER);
    ciPipelineUrl = System.getenv(JENKINS_PIPELINE_URL);
    ciJobUrl = System.getenv(JENKINS_JOB_URL);
    ciWorkspacePath = expandTilde(System.getenv(JENKINS_WORKSPACE_PATH));
    gitRepositoryUrl = filterSensitiveInfo(System.getenv(JENKINS_GIT_REPOSITORY_URL));
    gitCommit = System.getenv(JENKINS_GIT_COMMIT);

    final String gitBranchOrTag = System.getenv(JENKINS_GIT_BRANCH);
    if (gitBranchOrTag != null) {
      if (gitBranchOrTag.contains("tags")) {
        gitTag = normalizeRef(gitBranchOrTag);
      } else {
        gitBranch = normalizeRef(gitBranchOrTag);
      }
    }

    final String jobName = System.getenv(JENKINS_PIPELINE_NAME);
    ciPipelineName = filterJenkinsJobName(jobName, gitBranch);
  }

  private void setGitLabData() {
    isCI = true;
    ciProviderName = GITLAB_PROVIDER_NAME;
    ciPipelineId = System.getenv(GITLAB_PIPELINE_ID);
    ciPipelineName = System.getenv(GITLAB_PIPELINE_NAME);
    ciPipelineNumber = System.getenv(GITLAB_PIPELINE_NUMBER);
    ciPipelineUrl = System.getenv(GITLAB_PIPELINE_URL);
    ciJobUrl = System.getenv(GITLAB_JOB_URL);
    ciWorkspacePath = expandTilde(System.getenv(GITLAB_WORKSPACE_PATH));
    gitRepositoryUrl = filterSensitiveInfo(System.getenv(GITLAB_GIT_REPOSITORY_URL));
    gitCommit = System.getenv(GITLAB_GIT_COMMIT);
    gitBranch = normalizeRef(System.getenv(GITLAB_GIT_BRANCH));
    gitTag = normalizeRef(System.getenv(GITLAB_GIT_TAG));
  }

  private void setTravisData() {
    isCI = true;
    ciProviderName = TRAVIS_PROVIDER_NAME;
    ciPipelineId = System.getenv(TRAVIS_PIPELINE_ID);
    ciPipelineNumber = System.getenv(TRAVIS_PIPELINE_NUMBER);
    ciPipelineUrl = System.getenv(TRAVIS_PIPELINE_URL);
    ciJobUrl = System.getenv(TRAVIS_JOB_URL);
    ciWorkspacePath = expandTilde(System.getenv(TRAVIS_WORKSPACE_PATH));

    String repoSlug = System.getenv(TRAVIS_PR_REPOSITORY_SLUG);
    if (repoSlug == null || repoSlug.isEmpty()) {
      repoSlug = System.getenv(TRAVIS_REPOSITORY_SLUG);
    }
    ciPipelineName = repoSlug;
    // TravisCI only supports GitHub.
    gitRepositoryUrl = String.format("https://github.com/%s.git", repoSlug);

    gitCommit = System.getenv(TRAVIS_GIT_COMMIT);
    gitTag = normalizeRef(System.getenv(TRAVIS_GIT_TAG));

    if (gitTag == null || gitTag.isEmpty()) {
      final String fromBranch = System.getenv(TRAVIS_GIT_PR_BRANCH);
      if (fromBranch != null && !fromBranch.isEmpty()) {
        gitBranch = normalizeRef(fromBranch);
      } else {
        gitBranch = normalizeRef(System.getenv(TRAVIS_GIT_BRANCH));
      }
    }
  }

  private void setCircleCIData() {
    isCI = true;
    ciProviderName = CIRCLECI_PROVIDER_NAME;
    ciPipelineId = System.getenv(CIRCLECI_PIPELINE_ID);
    ciPipelineName = System.getenv(CIRCLECI_PIPELINE_NAME);
    ciPipelineNumber = System.getenv(CIRCLECI_PIPELINE_NUMBER);
    ciPipelineUrl = System.getenv(CIRCLECI_BUILD_URL);
    ciJobUrl = System.getenv(CIRCLECI_BUILD_URL);
    ciWorkspacePath = expandTilde(System.getenv(CIRCLECI_WORKSPACE_PATH));
    gitRepositoryUrl = filterSensitiveInfo(System.getenv(CIRCLECI_GIT_REPOSITORY_URL));
    gitCommit = System.getenv(CIRCLECI_GIT_COMMIT);
    gitTag = normalizeRef(System.getenv(CIRCLECI_GIT_TAG));
    if (gitTag == null || gitTag.isEmpty()) {
      gitBranch = normalizeRef(System.getenv(CIRCLECI_GIT_BRANCH));
    }
  }

  private void setAppveyorData() {
    isCI = true;
    ciProviderName = APPVEYOR_PROVIDER_NAME;

    final String buildId = System.getenv(APPVEYOR_BUILD_ID);
    final String repoName = System.getenv(APPVEYOR_REPO_NAME);
    ciPipelineId = buildId;
    ciPipelineName = repoName;
    ciPipelineNumber = System.getenv(APPVEYOR_PIPELINE_NUMBER);
    final String url =
        String.format("https://ci.appveyor.com/project/%s/builds/%s", repoName, buildId);
    ciPipelineUrl = url;
    ciJobUrl = url;
    ciWorkspacePath = expandTilde(System.getenv(APPVEYOR_WORKSPACE_PATH));

    final String provider = System.getenv(APPVEYOR_REPO_PROVIDER);
    if ("github".equals(provider)) {
      gitRepositoryUrl = String.format("https://github.com/%s.git", repoName);
      gitCommit = System.getenv(APPVEYOR_REPO_COMMIT);
      gitTag = normalizeRef(System.getenv(APPVEYOR_REPO_TAG_NAME));

      if (gitTag == null || gitTag.isEmpty()) {
        String branch = System.getenv(APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH);
        if (branch == null || branch.isEmpty()) {
          branch = System.getenv(APPVEYOR_REPO_BRANCH);
        }
        gitBranch = normalizeRef(branch);
      }
    }
  }

  private void setAzureData() {
    isCI = true;
    ciProviderName = AZURE_PROVIDER_NAME;
    ciPipelineId = System.getenv(AZURE_BUILD_BUILDID);
    ciPipelineName = System.getenv(AZURE_PIPELINE_NAME);
    ciPipelineNumber = System.getenv(AZURE_PIPELINE_NUMBER);
    ciWorkspacePath = expandTilde(System.getenv(AZURE_WORKSPACE_PATH));

    final String uri = System.getenv(AZURE_SYSTEM_TEAMFOUNDATIONSERVERURI);
    final String project = System.getenv(AZURE_SYSTEM_TEAMPROJECT);
    final String buildId = System.getenv(AZURE_BUILD_BUILDID);
    ciPipelineUrl =
        String.format("%s%s/_build/results?buildId=%s&_a=summary", uri, project, buildId);

    final String jobId = System.getenv(AZURE_SYSTEM_JOBID);
    final String taskId = System.getenv(AZURE_SYSTEM_TASKINSTANCEID);
    ciJobUrl =
        String.format(
            "%s%s/_build/results?buildId=%s&view=logs&j=%s&t=%s",
            uri, project, buildId, jobId, taskId);

    String repoUrl = System.getenv(AZURE_SYSTEM_PULLREQUEST_SOURCEREPOSITORYURI);
    if (repoUrl == null || repoUrl.isEmpty()) {
      repoUrl = System.getenv(AZURE_BUILD_REPOSITORY_URI);
    }
    gitRepositoryUrl = filterSensitiveInfo(repoUrl);

    String commit = System.getenv(AZURE_SYSTEM_PULLREQUEST_SOURCECOMMITID);
    if (commit == null || commit.isEmpty()) {
      commit = System.getenv(AZURE_BUILD_SOURCEVERSION);
    }
    gitCommit = commit;

    String branchOrTag = System.getenv(AZURE_SYSTEM_PULLREQUEST_SOURCEBRANCH);
    if (branchOrTag == null || branchOrTag.isEmpty()) {
      branchOrTag = System.getenv(AZURE_BUILD_SOURCEBRANCH);
    }
    if (branchOrTag != null) {
      if (branchOrTag.contains("tags")) {
        gitTag = normalizeRef(branchOrTag);
      } else {
        gitBranch = normalizeRef(branchOrTag);
      }
    }
  }

  private void setBitBucketData() {
    isCI = true;
    ciProviderName = BITBUCKET_PROVIDER_NAME;
    String id = System.getenv(BITBUCKET_PIPELINE_ID);
    if (id != null) {
      id = id.replaceAll("}", "").replaceAll("\\{", "");
    }
    ciPipelineId = id;
    final String repo = System.getenv(BITBUCKET_REPO_FULL_NAME);
    final String number = System.getenv(BITBUCKET_BUILD_NUMBER);
    ciPipelineName = repo;
    ciPipelineNumber = number;
    final String url =
        String.format("https://bitbucket.org/%s/addon/pipelines/home#!/results/%s", repo, number);
    ciPipelineUrl = url;
    ciJobUrl = url;
    ciWorkspacePath = expandTilde(System.getenv(BITBUCKET_WORKSPACE_PATH));
    gitRepositoryUrl = filterSensitiveInfo(System.getenv(BITBUCKET_GIT_REPOSITORY_URL));
    gitCommit = System.getenv(BITBUCKET_GIT_COMMIT);
    gitBranch = normalizeRef(System.getenv(BITBUCKET_GIT_BRANCH));
    gitTag = normalizeRef(System.getenv(BITBUCKET_GIT_TAG));
  }

  private void setGHActionsData() {
    isCI = true;
    ciProviderName = GHACTIONS_PROVIDER_NAME;
    ciPipelineId = System.getenv(GHACTIONS_PIPELINE_ID);
    ciPipelineName = System.getenv(GHACTIONS_PIPELINE_NAME);
    ciPipelineNumber = System.getenv(GHACTIONS_PIPELINE_NUMBER);

    final String repo = System.getenv(GHACTIONS_REPOSITORY);
    final String commit = System.getenv(GHACTIONS_SHA);
    final String url = String.format("https://github.com/%s/commit/%s/checks", repo, commit);
    ciPipelineUrl = url;
    ciJobUrl = url;

    ciWorkspacePath = expandTilde(System.getenv(GHACTIONS_WORKSPACE_PATH));
    gitRepositoryUrl = String.format("https://github.com/%s.git", repo);
    gitCommit = commit;

    String gitBranchOrTag = System.getenv(GHACTIONS_HEAD_REF);
    if (gitBranchOrTag == null || gitBranchOrTag.isEmpty()) {
      gitBranchOrTag = System.getenv(GHACTIONS_REF);
    }

    if (gitBranchOrTag != null) {
      if (gitBranchOrTag.contains("tags")) {
        gitTag = normalizeRef(gitBranchOrTag);
      } else {
        gitBranch = normalizeRef(gitBranchOrTag);
      }
    }
  }

  private void setBuildkiteData() {
    isCI = true;
    ciProviderName = BUILDKITE_PROVIDER_NAME;
    ciPipelineId = System.getenv(BUILDKITE_PIPELINE_ID);
    ciPipelineName = System.getenv(BUILDKITE_PIPELINE_NAME);
    ciPipelineNumber = System.getenv(BUILDKITE_PIPELINE_NUMBER);
    ciPipelineUrl = System.getenv(BUILDKITE_BUILD_URL);
    ciJobUrl = String.format("%s#%s", ciPipelineUrl, System.getenv(BUILDKITE_JOB_ID));
    ciWorkspacePath = expandTilde(System.getenv(BUILDKITE_WORKSPACE_PATH));
    gitRepositoryUrl = filterSensitiveInfo(System.getenv(BUILDKITE_GIT_REPOSITORY_URL));
    gitCommit = System.getenv(BUILDKITE_GIT_COMMIT);
    gitBranch = normalizeRef(System.getenv(BUILDKITE_GIT_BRANCH));
    gitTag = normalizeRef(System.getenv(BUILDKITE_GIT_TAG));
  }

  private String normalizeRef(final String rawRef) {
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

  private String filterSensitiveInfo(final String urlStr) {
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

  private String filterJenkinsJobName(final String jobName, final String gitBranch) {
    if (jobName == null) {
      return null;
    }

    // First, the git branch is removed from the raw jobName
    final String jobNameNoBranch;
    if (gitBranch != null && !gitBranch.isEmpty()) {
      jobNameNoBranch = jobName.trim().replace("/" + gitBranch, "");
    } else {
      jobNameNoBranch = jobName;
    }

    // Once the branch has been removed, we try to extract
    // the configurations from the job name.
    // The configurations have the form like "key1=value1,key2=value2"
    final Map<String, String> configurations = new HashMap<>();
    final String[] jobNameParts = jobNameNoBranch.split("/");
    if (jobNameParts.length > 1 && jobNameParts[1].contains("=")) {
      final String configsStr = jobNameParts[1].toLowerCase().trim();
      final String[] configsKeyValue = configsStr.split(",");
      for (final String configKeyValue : configsKeyValue) {
        final String[] keyValue = configKeyValue.trim().split("=");
        configurations.put(keyValue[0], keyValue[1]);
      }
    }

    if (configurations.isEmpty()) {
      // If there is no configurations,
      // the jobName is the original one without branch.
      return jobNameNoBranch;
    } else {
      // If there are configurations,
      // the jobName is the first part of the splited raw jobName.
      return jobNameParts[0];
    }
  }

  private String expandTilde(final String path) {
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

  public List<String> testNames(
      final Class<?> testClass, final Class<? extends Annotation> testAnnotation) {
    final List<String> testNames = new ArrayList<>();

    final Method[] methods = testClass.getMethods();
    for (final Method method : methods) {
      if (method.getAnnotation(testAnnotation) != null) {
        testNames.add(method.getName());
      }
    }
    return testNames;
  }
}
