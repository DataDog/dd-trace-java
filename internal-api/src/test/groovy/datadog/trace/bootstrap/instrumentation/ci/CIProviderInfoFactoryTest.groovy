package datadog.trace.bootstrap.instrumentation.ci


import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import org.junit.contrib.java.lang.system.RestoreSystemProperties
import spock.lang.Specification

import static AppVeyorInfo.APPVEYOR
import static AzurePipelinesInfo.AZURE
import static BitBucketInfo.BITBUCKET
import static BitriseInfo.BITRISE
import static BuildkiteInfo.BUILDKITE
import static CircleCIInfo.CIRCLECI
import static GitLabInfo.GITLAB
import static GithubActionsInfo.GHACTIONS
import static JenkinsInfo.JENKINS
import static TravisInfo.TRAVIS

class CIProviderInfoFactoryTest extends Specification {
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

  def "test correct info is selected"() {
    setup:
    environmentVariables.set(ciKeySelector, "true")

    when:
    def ciProviderInfo = CIProviderInfoFactory.createCIProviderInfo()

    then:
    ciProviderInfo.class == ciInfoClass

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
    "none"        | UnknownCIInfo
  }
}
