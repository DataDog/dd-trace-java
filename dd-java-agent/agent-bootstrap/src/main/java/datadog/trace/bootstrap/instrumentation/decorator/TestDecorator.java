package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.ci.CIProviderInfo;
import datadog.trace.api.sampling.PrioritySampling;
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

  @Getter private final boolean isCI;
  @Getter private final String ciProviderName;
  @Getter private final String ciPipelineId;
  @Getter private final String ciPipelineName;
  @Getter private final String ciPipelineNumber;
  @Getter private final String ciPipelineUrl;
  @Getter private final String ciJobUrl;
  @Getter private final String ciWorkspacePath;
  @Getter private final String gitRepositoryUrl;
  @Getter private final String gitCommit;
  @Getter private final String gitBranch;
  @Getter private final String gitTag;

  public TestDecorator() {
    this(CIProviderInfo.selectCI());
  }

  TestDecorator(final CIProviderInfo ciInfo) {
    this.isCI = ciInfo.isCI();
    this.ciProviderName = ciInfo.getCiProviderName();
    this.ciPipelineId = ciInfo.getCiPipelineId();
    this.ciPipelineName = ciInfo.getCiPipelineName();
    this.ciPipelineNumber = ciInfo.getCiPipelineNumber();
    this.ciPipelineUrl = ciInfo.getCiPipelineUrl();
    this.ciJobUrl = ciInfo.getCiJobUrl();
    this.ciWorkspacePath = ciInfo.getCiWorkspacePath();
    this.gitRepositoryUrl = ciInfo.getGitRepositoryUrl();
    this.gitCommit = ciInfo.getGitCommit();
    this.gitBranch = ciInfo.getGitBranch();
    this.gitTag = ciInfo.getGitTag();
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
    span.setTag(DDTags.TEST_FRAMEWORK, testFramework());
    span.setTag(DDTags.TEST_TYPE, testType());
    span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);

    span.setTag(DDTags.CI_PROVIDER_NAME, ciProviderName);
    span.setTag(DDTags.CI_PIPELINE_ID, ciPipelineId);
    span.setTag(DDTags.CI_PIPELINE_NAME, ciPipelineName);
    span.setTag(DDTags.CI_PIPELINE_NUMBER, ciPipelineNumber);
    span.setTag(DDTags.CI_PIPELINE_URL, ciPipelineUrl);
    span.setTag(DDTags.CI_JOB_URL, ciJobUrl);
    span.setTag(DDTags.CI_WORKSPACE_PATH, ciWorkspacePath);
    span.setTag(DDTags.BUILD_SOURCE_ROOT, ciWorkspacePath);

    span.setTag(DDTags.GIT_REPOSITORY_URL, gitRepositoryUrl);
    span.setTag(DDTags.GIT_COMMIT_SHA, gitCommit);
    span.setTag(DDTags._GIT_COMMIT_SHA, gitCommit);
    span.setTag(DDTags.GIT_BRANCH, gitBranch);
    span.setTag(DDTags.GIT_TAG, gitTag);

    return super.afterStart(span);
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
