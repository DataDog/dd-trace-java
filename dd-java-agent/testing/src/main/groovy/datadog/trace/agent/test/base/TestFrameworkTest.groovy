package datadog.trace.agent.test.base

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator
import spock.lang.Shared
import spock.lang.Unroll

@Unroll
abstract class TestFrameworkTest extends AgentTestRunner {

  static {
    ConfigUtils.updateConfig {
      System.setProperty("dd.integration.junit.enabled", "true")
      System.setProperty("dd.integration.testng.enabled", "true")
    }
  }

  void testSpan(TraceAssert trace, int index, final String testSuite, final String testName, final String testStatus, final Map<String, String> testTags = null, final Throwable exception = null) {
    def testFramework = expectedTestFramework()

    trace.span {
      parent()
      operationName expectedOperationName()
      resourceName "$testSuite.$testName"
      spanType DDSpanTypes.TEST
      errored exception != null
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_TEST
        "$Tags.TEST_TYPE" TestDecorator.TEST_TYPE
        "$Tags.TEST_SUITE" testSuite
        "$Tags.TEST_NAME" testName
        "$Tags.TEST_FRAMEWORK" testFramework
        "$Tags.TEST_STATUS" testStatus
        if (testTags) {
          testTags.each { key, val -> tag(key, val) }
        }

        if (exception) {
          errorTags(exception.class, exception.message)
        }

        if (isCI) {
          "$Tags.CI_PROVIDER_NAME" ciProviderName
          "$Tags.CI_PIPELINE_ID" ciPipelineId
          "$Tags.CI_PIPELINE_NAME" ciPipelineName
          "$Tags.CI_PIPELINE_NUMBER" ciPipelineNumber
          "$Tags.CI_PIPELINE_URL" ciPipelineUrl
          "$Tags.CI_JOB_URL" ciJobUrl
          "$Tags.CI_WORKSPACE_PATH" ciWorkspacePath
          "$Tags.BUILD_SOURCE_ROOT" ciWorkspacePath
          "$Tags.GIT_REPOSITORY_URL" gitRepositoryUrl
          "$Tags.GIT_COMMIT_SHA" gitCommit
          "$Tags.GIT_BRANCH" gitBranch
          "$Tags.GIT_TAG" gitTag
        }

        defaultTags()
      }
    }
  }

  @Shared
  String component = component()

  @Shared
  boolean isCI = isCI()
  @Shared
  String ciProviderName = ciProviderName()
  @Shared
  String ciPipelineId = ciPipelineId()
  @Shared
  String ciPipelineName = ciPipelineName()
  @Shared
  String ciPipelineNumber = ciPipelineNumber()
  @Shared
  String ciPipelineUrl = ciPipelineUrl()
  @Shared
  String ciJobUrl = ciJobUrl()
  @Shared
  String ciWorkspacePath = ciWorkspacePath()
  @Shared
  String gitRepositoryUrl = gitRepositoryUrl()
  @Shared
  String gitCommit = gitCommit()
  @Shared
  String gitBranch = gitBranch()
  @Shared
  String gitTag = gitTag()

  abstract String expectedOperationName()

  abstract String expectedTestFramework()

  abstract String component()

  abstract boolean isCI()

  abstract String ciProviderName()

  abstract String ciPipelineId()

  abstract String ciPipelineName()

  abstract String ciPipelineNumber()

  abstract String ciPipelineUrl()

  abstract String ciJobUrl()

  abstract String ciWorkspacePath()

  abstract String gitRepositoryUrl()

  abstract String gitCommit()

  abstract String gitBranch()

  abstract String gitTag()

}
