package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
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
    span.setTag(DDTags.SPAN_TYPE, spanType());
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
    ciWorkspacePath = System.getenv(JENKINS_WORKSPACE_PATH);
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
    ciWorkspacePath = System.getenv(GITLAB_WORKSPACE_PATH);
    gitRepositoryUrl = filterSensitiveInfo(System.getenv(GITLAB_GIT_REPOSITORY_URL));
    gitCommit = System.getenv(GITLAB_GIT_COMMIT);
    gitBranch = normalizeRef(System.getenv(GITLAB_GIT_BRANCH));
    gitTag = normalizeRef(System.getenv(GITLAB_GIT_TAG));
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
