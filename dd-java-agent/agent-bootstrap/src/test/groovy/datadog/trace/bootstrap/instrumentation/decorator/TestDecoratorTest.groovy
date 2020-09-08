package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables

import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_GIT_REPOSITORY_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_GIT_TAG
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_JOB_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_PIPELINE_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_WORKSPACE_PATH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_GIT_REPOSITORY_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_JOB_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_PIPELINE_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_WORKSPACE_PATH

class TestDecoratorTest extends BaseDecoratorTest {

  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    setup:
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setTag(DDTags.SPAN_TYPE, decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setTag(DDTags.MANUAL_SAMPLER_KEEP, true)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    0 * _

    where:
    serviceName << ["test-service", "other-service", null]
  }

  def "test afterStart in Jenkins"() {
    setup:
    environmentVariables.set(JENKINS, "jenkins")
    environmentVariables.set(JENKINS_PIPELINE_ID, "jenkins-pipeline-id")
    environmentVariables.set(JENKINS_PIPELINE_NUMBER, "jenkins-pipeline-number")
    environmentVariables.set(JENKINS_PIPELINE_URL, "jenkins-pipeline-url")
    environmentVariables.set(JENKINS_JOB_URL, "jenkins-job-url")
    environmentVariables.set(JENKINS_WORKSPACE_PATH, "jenkins-workspace-path")
    environmentVariables.set(JENKINS_GIT_REPOSITORY_URL, "jenkins-git-repo-url")
    environmentVariables.set(JENKINS_GIT_COMMIT, "jenkins-git-commit")
    environmentVariables.set(JENKINS_GIT_BRANCH, jenkinsBranch)
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setTag(DDTags.SPAN_TYPE, decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setTag(Tags.CI_PROVIDER_NAME, JENKINS_PROVIDER_NAME)
    1 * span.setTag(Tags.CI_PIPELINE_ID, "jenkins-pipeline-id")
    1 * span.setTag(Tags.CI_PIPELINE_NUMBER, "jenkins-pipeline-number")
    1 * span.setTag(Tags.CI_PIPELINE_URL, "jenkins-pipeline-url")
    1 * span.setTag(Tags.CI_JOB_URL, "jenkins-job-url")
    1 * span.setTag(Tags.CI_WORKSPACE_PATH, "jenkins-workspace-path")
    1 * span.setTag(Tags.GIT_REPOSITORY_URL, "jenkins-git-repo-url")
    1 * span.setTag(Tags.GIT_COMMIT_SHA, "jenkins-git-commit")
    1 * span.setTag(Tags.GIT_BRANCH, spanTagBranch)
    1 * span.setTag(Tags.GIT_TAG, spanTagTag)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    0 * _

    where:
    jenkinsBranch            | spanTagBranch | spanTagTag
    "origin/master"          | "master"      | null
    "refs/heads/master"      | "master"      | null
    "refs/heads/feature/one" | "feature/one" | null
    "origin/tags/0.1.0"      | null          | "0.1.0"
    "refs/heads/tags/0.1.0"  | null          | "0.1.0"
  }

  def "test afterStart in GitLab"() {
    setup:
    environmentVariables.set(GITLAB, "gitlab")
    environmentVariables.set(GITLAB_PIPELINE_ID, "gitlab-pipeline-id")
    environmentVariables.set(GITLAB_PIPELINE_NUMBER, "gitlab-pipeline-number")
    environmentVariables.set(GITLAB_PIPELINE_URL, "gitlab-pipeline-url")
    environmentVariables.set(GITLAB_JOB_URL, "gitlab-job-url")
    environmentVariables.set(GITLAB_WORKSPACE_PATH, "gitlab-workspace-path")
    environmentVariables.set(GITLAB_GIT_REPOSITORY_URL, "gitlab-git-repo-url")
    environmentVariables.set(GITLAB_GIT_COMMIT, "gitlab-git-commit")
    environmentVariables.set(GITLAB_GIT_BRANCH, gitlabBranch)
    environmentVariables.set(GITLAB_GIT_TAG, gitlabTag)
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setTag(DDTags.SPAN_TYPE, decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setTag(Tags.CI_PROVIDER_NAME, GITLAB_PROVIDER_NAME)
    1 * span.setTag(Tags.CI_PIPELINE_ID, "gitlab-pipeline-id")
    1 * span.setTag(Tags.CI_PIPELINE_NUMBER, "gitlab-pipeline-number")
    1 * span.setTag(Tags.CI_PIPELINE_URL, "gitlab-pipeline-url")
    1 * span.setTag(Tags.CI_JOB_URL, "gitlab-job-url")
    1 * span.setTag(Tags.CI_WORKSPACE_PATH, "gitlab-workspace-path")
    1 * span.setTag(Tags.GIT_REPOSITORY_URL, "gitlab-git-repo-url")
    1 * span.setTag(Tags.GIT_COMMIT_SHA, "gitlab-git-commit")
    1 * span.setTag(Tags.GIT_BRANCH, spanTagBranch)
    1 * span.setTag(Tags.GIT_TAG, spanTagTag)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    0 * _

    where:
    gitlabBranch             | gitlabTag               | spanTagBranch | spanTagTag
    "origin/master"          | null                    | "master"      | null
    "refs/heads/master"      | null                    | "master"      | null
    "refs/heads/feature/one" | null                    | "feature/one" | null
    null                     | "origin/tags/0.1.0"     | null          | "0.1.0"
    null                     | "refs/heads/tags/0.1.0" | null          | "0.1.0"
    null                     | "0.1.0"                 | null          | "0.1.0"
  }

  def "test beforeFinish"() {
    when:
    newDecorator().beforeFinish(span)

    then:
    0 * _
  }

  @Override
  def newDecorator() {
    return new TestDecorator() {
      @Override
      protected String testFramework() {
        return "test-framework"
      }

      @Override
      protected String[] instrumentationNames() {
        return ["test1", "test2"]
      }

      @Override
      protected String spanType() {
        return "test-type"
      }

      @Override
      protected String spanKind() {
        return "test-type"
      }

      @Override
      protected String component() {
        return "test-component"
      }
    }
  }
}
