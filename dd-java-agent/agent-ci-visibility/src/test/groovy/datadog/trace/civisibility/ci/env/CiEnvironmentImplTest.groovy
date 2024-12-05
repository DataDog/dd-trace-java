package datadog.trace.civisibility.ci.env


import spock.lang.Specification

class CiEnvironmentImplTest extends Specification {

  def "test returns an environment variable"() {
    setup:
    def environmentVariables = ["MY_ENV_VAR": "MY_VALUE"]
    def environment = new CiEnvironmentImpl(environmentVariables)

    expect:
    environment.get("MY_ENV_VAR") == "MY_VALUE"
  }

  def "test returns all environment variables"() {
    setup:
    def environmentVariables = ["MY_ENV_VAR": "MY_VALUE", "MY_OTHER_ENV_VAR": "MY_OTHER_VALUE"]
    def environment = new CiEnvironmentImpl(environmentVariables)

    expect:
    environment.get() == ["MY_ENV_VAR": "MY_VALUE", "MY_OTHER_ENV_VAR": "MY_OTHER_VALUE"]
  }
}
