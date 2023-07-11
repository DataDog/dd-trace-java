package datadog.trace.civisibility.ci

import datadog.trace.api.Config
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import org.junit.contrib.java.lang.system.RestoreSystemProperties
import spock.lang.Specification

import java.nio.file.Paths

import static datadog.trace.civisibility.ci.AppVeyorInfo.APPVEYOR
import static datadog.trace.civisibility.ci.AzurePipelinesInfo.AZURE
import static datadog.trace.civisibility.ci.BitBucketInfo.BITBUCKET
import static datadog.trace.civisibility.ci.BitriseInfo.BITRISE
import static datadog.trace.civisibility.ci.BuildkiteInfo.BUILDKITE
import static datadog.trace.civisibility.ci.CircleCIInfo.CIRCLECI
import static datadog.trace.civisibility.ci.GitLabInfo.GITLAB
import static datadog.trace.civisibility.ci.GithubActionsInfo.GHACTIONS
import static datadog.trace.civisibility.ci.JenkinsInfo.JENKINS
import static datadog.trace.civisibility.ci.TravisInfo.TRAVIS

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
    def ciProviderInfoFactory = new CIProviderInfoFactory(Config.get())
    def ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(Paths.get("").toAbsolutePath())

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
