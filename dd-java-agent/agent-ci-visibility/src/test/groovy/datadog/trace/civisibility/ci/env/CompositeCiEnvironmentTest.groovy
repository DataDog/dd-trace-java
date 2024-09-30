package datadog.trace.civisibility.ci.env


import spock.lang.Specification

class CompositeCiEnvironmentTest extends Specification {

  def "test uses delegate"() {
    setup:
    def environmentVariables = ["MY_ENV_VAR": "MY_VALUE", "MY_OTHER_ENV_VAR": "MY_OTHER_VALUE"]
    def delegate = new CiEnvironmentImpl(environmentVariables)
    def environment = new CompositeCiEnvironment(delegate)

    expect:
    environment.get("MY_ENV_VAR") == "MY_VALUE"
    environment.get() == ["MY_ENV_VAR": "MY_VALUE", "MY_OTHER_ENV_VAR": "MY_OTHER_VALUE"]
  }

  def "test uses multiple delegates"() {
    setup:
    def delegateA = new CiEnvironmentImpl(["A": "1", "AA": "2"])
    def delegateB = new CiEnvironmentImpl(["B": "3", "BB": "4"])
    def environment = new CompositeCiEnvironment(delegateA, delegateB)

    expect:
    environment.get("A") == "1"
    environment.get("B") == "3"
    environment.get() == ["A": "1", "AA": "2", "B": "3", "BB": "4"]
  }

  def "test delegates priority"() {
    setup:
    def delegateA = new CiEnvironmentImpl(["A": "1", "B": "2"])
    def delegateB = new CiEnvironmentImpl(["B": "3", "C": "4"])
    def environment = new CompositeCiEnvironment(delegateA, delegateB)

    expect:
    environment.get("B") == "2"
    environment.get() == ["A": "1", "B": "2", "C": "4"]
  }
}
