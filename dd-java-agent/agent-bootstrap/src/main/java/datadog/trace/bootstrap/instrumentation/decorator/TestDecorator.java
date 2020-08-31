package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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
  static final String JENKINS_JOB_URL = "JOB_URL";
  static final String JENKINS_WORKSPACE_PATH = "WORKSPACE";
  static final String JENKINS_GIT_REPOSITORY_URL = "GIT_URL";
  static final String JENKINS_GIT_COMMIT = "GIT_COMMIT";
  static final String JENKINS_GIT_BRANCH = "GIT_BRANCH";

  public static final String GITLAB = "GITLAB_CI";
  static final String GITLAB_PROVIDER_NAME = "gitlab";
  static final String GITLAB_PIPELINE_ID = "CI_PIPELINE_ID";
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
  protected String spanType() {
    return DDSpanTypes.TEST;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    assert span != null;
    span.setTag(Tags.SPAN_KIND, spanKind());
    span.setTag(DDTags.SPAN_TYPE, spanType());
    span.setTag(DDTags.TEST_FRAMEWORK, testFramework());
    span.setTag(DDTags.TEST_TYPE, testType());

    span.setTag(DDTags.CI_PROVIDER_NAME, ciProviderName);
    span.setTag(DDTags.CI_PIPELINE_ID, ciPipelineId);
    span.setTag(DDTags.CI_PIPELINE_NUMBER, ciPipelineNumber);
    span.setTag(DDTags.CI_PIPELINE_URL, ciPipelineUrl);
    span.setTag(DDTags.CI_JOB_URL, ciJobUrl);
    span.setTag(DDTags.CI_WORKSPACE_PATH, ciWorkspacePath);

    span.setTag(DDTags.GIT_REPOSITORY_URL, gitRepositoryUrl);
    span.setTag(DDTags.GIT_COMMIT_SHA, gitCommit);
    span.setTag(DDTags.GIT_BRANCH, normalizeRef(gitBranch));
    span.setTag(DDTags.GIT_TAG, normalizeRef(gitTag));

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
    gitRepositoryUrl = System.getenv(JENKINS_GIT_REPOSITORY_URL);
    gitCommit = System.getenv(JENKINS_GIT_COMMIT);

    final String gitBranchOrTag = System.getenv(JENKINS_GIT_BRANCH);
    if (gitBranchOrTag != null) {
      if (gitBranchOrTag.contains("tags")) {
        gitTag = gitBranchOrTag;
      } else {
        gitBranch = gitBranchOrTag;
      }
    }
  }

  private void setGitLabData() {
    isCI = true;
    ciProviderName = GITLAB_PROVIDER_NAME;
    ciPipelineId = System.getenv(GITLAB_PIPELINE_ID);
    ciPipelineNumber = System.getenv(GITLAB_PIPELINE_NUMBER);
    ciPipelineUrl = System.getenv(GITLAB_PIPELINE_URL);
    ciJobUrl = System.getenv(GITLAB_JOB_URL);
    ciWorkspacePath = System.getenv(GITLAB_WORKSPACE_PATH);
    gitRepositoryUrl = System.getenv(GITLAB_GIT_REPOSITORY_URL);
    gitCommit = System.getenv(GITLAB_GIT_COMMIT);
    gitBranch = System.getenv(GITLAB_GIT_BRANCH);
    gitTag = System.getenv(GITLAB_GIT_TAG);
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
