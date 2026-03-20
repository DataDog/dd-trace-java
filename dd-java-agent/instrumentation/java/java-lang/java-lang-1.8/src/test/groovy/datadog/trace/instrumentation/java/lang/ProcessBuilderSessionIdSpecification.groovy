package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Config

class ProcessBuilderSessionIdSpecification extends AgentTestRunner {

  def "ProcessBuilder injects root session ID into child environment"() {
    setup:
    def command = ['sh', '-c', 'echo $_DD_ROOT_JAVA_SESSION_ID']
    def pb = new ProcessBuilder(command)

    when:
    def process = pb.start()
    def output = process.inputStream.text.trim()
    process.waitFor()

    then:
    output == Config.get().getRootSessionId()
  }

  def "child process inherits root session ID not runtime ID"() {
    setup:
    def command = ['sh', '-c', 'echo $_DD_ROOT_JAVA_SESSION_ID']
    def pb = new ProcessBuilder(command)

    when:
    def process = pb.start()
    def output = process.inputStream.text.trim()
    process.waitFor()

    then:
    output == Config.get().getRootSessionId()
    Config.get().getRootSessionId() == Config.get().getRuntimeId()
  }
}
