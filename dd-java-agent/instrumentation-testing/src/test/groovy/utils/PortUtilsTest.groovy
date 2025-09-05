package utils

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.PortUtils

import java.util.concurrent.TimeUnit

class PortUtilsTest extends InstrumentationSpecification {
  def "expect waitForPortToOpen succeed"() {
    given:
    int port = PortUtils.randomOpenPort()
    def socket = new ServerSocket(port) // Emulating port opened.

    def process = Mock(Process)
    process.isAlive() >> true

    when:
    PortUtils.waitForPortToOpen(port, 1, TimeUnit.SECONDS, process)

    then:
    noExceptionThrown()

    cleanup:
    socket.close()
  }

  def "expect to handle port open timeout"() {
    given:
    int port = PortUtils.randomOpenPort()
    def process = Mock(Process)
    process.isAlive() >> true

    when:
    PortUtils.waitForPortToOpen(port, 1, TimeUnit.SECONDS, process)

    then:
    def ex = thrown(RuntimeException)
    ex.message.startsWith("Timed out waiting for port $port to be opened, started to wait at:")
  }

  def "expect to handle process abnormal termination"() {
    given:
    int port = PortUtils.randomOpenPort()
    def process = Mock(Process)
    process.isAlive() >> false
    process.exitValue() >> 123

    when:
    PortUtils.waitForPortToOpen(port, 1, TimeUnit.SECONDS, process)

    then:
    def ex = thrown(RuntimeException)
    ex.message == "Process exited abnormally exitCode=123 before port=$port was opened"
  }

  def "expect to handle process termination before port opened"() {
    given:
    int port = PortUtils.randomOpenPort()
    def process = Mock(Process)
    process.isAlive() >> false
    process.exitValue() >> 0

    when:
    PortUtils.waitForPortToOpen(port, 1, TimeUnit.SECONDS, process)

    then:
    def ex = thrown(RuntimeException)
    ex.message == "Process finished before port=$port was opened"
  }
}
