package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.DDTags
import datadog.trace.api.ci.CIProviderInfo
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags

class TestDecoratorTest extends BaseDecoratorTest {

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    setup:
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(DDTags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(DDTags.TEST_TYPE, decorator.testType())
    1 * span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP)
    1 * span.setTag(DDTags.CI_PROVIDER_NAME, "ci-provider-name")
    1 * span.setTag(DDTags.CI_PIPELINE_ID, "ci-pipeline-id")
    1 * span.setTag(DDTags.CI_PIPELINE_NAME, "ci-pipeline-name")
    1 * span.setTag(DDTags.CI_PIPELINE_NUMBER, "ci-pipeline-number")
    1 * span.setTag(DDTags.CI_PIPELINE_URL, "ci-pipeline-url")
    1 * span.setTag(DDTags.CI_JOB_URL, "ci-job-url")
    1 * span.setTag(DDTags.CI_WORKSPACE_PATH, "ci-workspace-path")
    1 * span.setTag(DDTags.BUILD_SOURCE_ROOT, "ci-workspace-path")
    1 * span.setTag(DDTags.GIT_REPOSITORY_URL, "git-repository-url")
    1 * span.setTag(DDTags.GIT_COMMIT_SHA, "git-commit")
    1 * span.setTag(DDTags.GIT_BRANCH, "git-branch")
    1 * span.setTag(DDTags.GIT_TAG, "git-tag")
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    0 * _

    where:
    serviceName << ["test-service", "other-service", null]
  }

  def "test beforeFinish"() {
    when:
    newDecorator().beforeFinish(span)

    then:
    0 * _
  }

  @Override
  def newDecorator() {
    return new TestDecorator(newMockCiInfo()) {
      @Override
      protected String testFramework() {
        return "test-framework"
      }

      @Override
      protected String[] instrumentationNames() {
        return ["test1", "test2"]
      }

      @Override
      protected CharSequence spanType() {
        return "test-type"
      }

      @Override
      protected String spanKind() {
        return "test-type"
      }

      @Override
      protected CharSequence component() {
        return "test-component"
      }
    }
  }

  def newMockCiInfo() {
    return new CIProviderInfo() {
      @Override
      String getCiProviderName() {
        return "ci-provider-name"
      }

      @Override
      String getCiPipelineId() {
        return "ci-pipeline-id"
      }

      @Override
      String getCiPipelineName() {
        return "ci-pipeline-name"
      }

      @Override
      String getCiPipelineNumber() {
        return "ci-pipeline-number"
      }

      @Override
      String getCiPipelineUrl() {
        return "ci-pipeline-url"
      }

      @Override
      String getCiJobUrl() {
        return "ci-job-url"
      }

      @Override
      String getCiWorkspacePath() {
        return "ci-workspace-path"
      }

      @Override
      String getGitRepositoryUrl() {
        return "git-repository-url"
      }

      @Override
      String getGitCommit() {
        return "git-commit"
      }

      @Override
      String getGitBranch() {
        return "git-branch"
      }

      @Override
      String getGitTag() {
        return "git-tag"
      }
    }
  }
}
