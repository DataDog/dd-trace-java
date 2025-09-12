package datadog.trace.civisibility.ci

import datadog.trace.api.Config
import datadog.trace.civisibility.ci.env.CiEnvironmentImpl
import spock.lang.Specification
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub

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
  @SystemStub
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

  def "test correct info is selected"() {
    setup:
    environmentVariables.set(ciKeySelector, "true")

    when:
    def ciProviderInfoFactory = new CIProviderInfoFactory(Config.get(), new CiEnvironmentImpl(System.getenv()))
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
