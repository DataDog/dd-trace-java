package datadog.common.container

import datadog.trace.test.util.DDSpecification

class ServerlessInfoTest extends DDSpecification {

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

  def "test serverless hasExtension false"() {
    when:
    def info = new ServerlessInfo()
    then:
    info.hasExtension() == false
  }

  def "test serverless hasExtension false since the extension path is null"() {
    when:
    def info = new ServerlessInfo(null)
    then:
    info.hasExtension() == false
  }

  def "test serverless hasExtension true"() {
    when:
    File f = File.createTempFile("fake-", "extension")
    f.deleteOnExit()
    def info = new ServerlessInfo(f.getAbsolutePath())
    then:
    info.hasExtension() == true
  }
}
