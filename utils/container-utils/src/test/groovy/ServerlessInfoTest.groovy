import datadog.common.container.ServerlessInfo
import datadog.trace.util.test.DDSpecification
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables

class ServerlessInfoTest extends DDSpecification {
  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

  def "test serverless detection"() {
    given:
    environmentVariables.set(ServerlessInfo.AWS_FUNCTION_VARIABLE, functionName)

    when:
    def info = new ServerlessInfo()

    then:
    info.runningInServerlessEnvironment == serverlessEnv
    info.functionName == functionName

    where:
    functionName | serverlessEnv
    null         | false
    ""           | false
    "someName"   | true
  }

}
