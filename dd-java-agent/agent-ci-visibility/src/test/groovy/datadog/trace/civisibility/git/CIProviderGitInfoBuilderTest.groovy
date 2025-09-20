package datadog.trace.civisibility.git

import datadog.trace.api.Config
import datadog.trace.civisibility.ci.env.CiEnvironmentImpl
import datadog.trace.test.util.ControllableEnvironmentVariables
import spock.lang.Specification

class CIProviderGitInfoBuilderTest extends Specification {
  protected ControllableEnvironmentVariables env = ControllableEnvironmentVariables.setup()

  void cleanup() {
    env.clear()
  }

  def "test builds empty git info in an unknown repository"() {
    setup:
    def builder = new CIProviderGitInfoBuilder(Config.get(), new CiEnvironmentImpl(env.getAll()))

    when:
    def gitInfo = builder.build(null)

    then:
    gitInfo.isEmpty()
  }
}
