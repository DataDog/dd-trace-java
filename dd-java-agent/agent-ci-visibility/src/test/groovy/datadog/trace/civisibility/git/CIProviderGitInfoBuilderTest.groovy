package datadog.trace.civisibility.git

import datadog.trace.api.Config
import datadog.trace.civisibility.ci.env.CiEnvironmentImpl
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import spock.lang.Specification

class CIProviderGitInfoBuilderTest extends Specification {

  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

  def setup() {
    // Clear all environment variables to avoid clashes between
    // real CI/Git environment variables and the spec CI/Git
    // environment variables.
    environmentVariables.clear(System.getenv().keySet() as String[])
  }

  def "test builds empty git info in an unknown repository"() {
    setup:
    def builder = new CIProviderGitInfoBuilder(Config.get(), new CiEnvironmentImpl(System.getenv()))

    when:
    def gitInfo = builder.build(null)

    then:
    gitInfo.isEmpty()
  }
}
