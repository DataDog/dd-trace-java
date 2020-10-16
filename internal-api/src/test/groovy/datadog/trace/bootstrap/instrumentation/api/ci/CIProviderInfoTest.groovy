package datadog.trace.bootstrap.instrumentation.api.ci

import datadog.trace.test.util.DDSpecification
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import spock.lang.Shared

import static datadog.trace.bootstrap.instrumentation.api.ci.AppVeyorInfo.APPVEYOR
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE
import static datadog.trace.bootstrap.instrumentation.api.ci.CIProviderInfo.selectCI
import static datadog.trace.bootstrap.instrumentation.api.ci.CircleCIInfo.CIRCLECI
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB
import static datadog.trace.bootstrap.instrumentation.api.ci.GithubActionsInfo.GHACTIONS
import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS
import static datadog.trace.bootstrap.instrumentation.api.ci.TravisInfo.TRAVIS

abstract class CIProviderInfoTest extends DDSpecification {

  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

  @Shared
  def userHome = System.getProperty("user.home")

  def setup() {
    // Clear all environment variables used to decide which CI/Git data
    // must be set in the Test span (See TestDecorator constructor).
    // Add the new CI envvar discriminant here if other CI provider is added.
    environmentVariables.clear(JENKINS, GITLAB, TRAVIS, CIRCLECI, APPVEYOR, AZURE, BITBUCKET, GHACTIONS, BUILDKITE)
  }

  def "test correct info is selected"() {
    setup:
    environmentVariables.set(ciKeySelector, "true")

    when:
    def ciInfo = selectCI()

    then:
    ciInfo.class == ciInfoClass

    where:
    ciKeySelector | ciInfoClass
    JENKINS       | JenkinsInfo
    GITLAB        | GitLabInfo
    TRAVIS        | TravisInfo
    CIRCLECI      | CircleCIInfo
    APPVEYOR      | AppVeyorInfo
    AZURE         | AzurePipelinesInfo
    GHACTIONS     | GithubActionsInfo
    BITBUCKET     | BitBucketInfo
    BUILDKITE     | BuildkiteInfo
    "none"        | NoopCIInfo
  }
}
