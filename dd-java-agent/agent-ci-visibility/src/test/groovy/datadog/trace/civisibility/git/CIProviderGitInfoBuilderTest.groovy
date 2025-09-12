package datadog.trace.civisibility.git

import datadog.trace.api.Config
import datadog.trace.civisibility.ci.env.CiEnvironmentImpl
import spock.lang.Specification
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables

class CIProviderGitInfoBuilderTest extends Specification {

  @SystemStub
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

  def "test builds empty git info in an unknown repository"() {
    setup:
    def builder = new CIProviderGitInfoBuilder(Config.get(), new CiEnvironmentImpl(System.getenv()))

    when:
    def gitInfo = builder.build(null)

    then:
    gitInfo.isEmpty()
  }
}
