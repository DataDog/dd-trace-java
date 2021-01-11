package datadog.trace.bootstrap.instrumentation.api.ci

import datadog.trace.test.util.DDSpecification
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import org.junit.contrib.java.lang.system.RestoreSystemProperties

import static datadog.trace.bootstrap.instrumentation.api.ci.AppVeyorInfo.APPVEYOR
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET
import static datadog.trace.bootstrap.instrumentation.api.ci.BitriseInfo.BITRISE
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

  @Rule
  public final RestoreSystemProperties restoreSystemProperties = new RestoreSystemProperties()

  def setup() {
    // Clear all environment variables to avoid clashes between
    // real CI/Git environment variables and the spec CI/Git
    // environment variables.
    environmentVariables.clear(System.getenv().keySet() as String[])
  }

  def "test ci provider info is set properly"() {
    setup:
    ciSpec.env.each {
      environmentVariables.set(it.key, it.value)
      if (it.key == "HOME") {
        System.setProperty("user.home", it.value)
      }
    }

    when:
    def ciInfo = instanceProvider()

    then:
    ciInfo.ciTags == ciSpec.tags

    where:
    ciSpec << CISpecExtractor.extract(getProviderName())
  }

  abstract CIProviderInfo instanceProvider()

  abstract String getProviderName()

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
    BITRISE       | BitriseInfo
    "none"        | NoopCIInfo
  }
}
