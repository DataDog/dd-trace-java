package datadog.trace.util

import datadog.trace.test.util.DDSpecification
import spock.util.concurrent.PollingConditions

// This test looks at the private "currentProcess" variable because the alternative
// would be calling "ps -e" repeatedly
class ProcessSupervisorTest extends DDSpecification {
  ProcessBuilder createProcessBuilder() {
    // Creates a process that never returns
    return new ProcessBuilder("tail", "-f", "/dev/null")
  }

  def "Process killed when supervisor closed"() {
    setup:
    def processBuilder = createProcessBuilder()
    def conditions = new PollingConditions(timeout: 11)

    when:
    def processSupervisor = new ProcessSupervisor("test", processBuilder)

    then:
    conditions.eventually {
      def process = processSupervisor.currentProcess
      assert process != null
      assert process.isAlive()
    }

    when:
    // code coverage: give the supervisor thread a reasonable chance to start waiting for the exit code
    Thread.sleep(1000)
    // code coverage: make sure that the supervisor thread loops around once
    processSupervisor.supervisorThread.interrupt()
    // code coverage: give the supervisor thread a reasonable chance to start waiting for the exit code
    Thread.sleep(1000)
    def oldProcess = processSupervisor.currentProcess
    processSupervisor.close()

    then:
    oldProcess != null
    conditions.eventually {
      assert !oldProcess.isAlive()
      def process = processSupervisor.currentProcess
      assert process == null || !process.isAlive()
    }
  }

  def "Process respawns when killed"() {
    setup:
    def processBuilder = createProcessBuilder()
    def conditions = new PollingConditions(timeout: 11)

    when:
    def processSupervisor = new ProcessSupervisor("test", processBuilder)

    then:
    conditions.eventually {
      def process = processSupervisor.currentProcess
      assert process != null
      assert process.isAlive()
    }

    when:
    // code coverage: give the supervisor thread a reasonable chance to start waiting for the exit code
    Thread.sleep(1000)
    def firstProcess = processSupervisor.currentProcess
    firstProcess.destroyForcibly()

    then:
    firstProcess != null
    conditions.eventually {
      assert !firstProcess.isAlive()
      def process = processSupervisor.currentProcess
      assert process != null
      assert process != firstProcess
      assert process.isAlive()
    }

    when:
    // code coverage: give the supervisor thread a reasonable chance to start waiting for the exit code
    Thread.sleep(1000)
    def secondProcess = processSupervisor.currentProcess
    processSupervisor.close()

    then:
    secondProcess != null
    conditions.eventually {
      assert !secondProcess.isAlive()
      def process = processSupervisor.currentProcess
      assert process == null || !process.isAlive()
    }
  }
}
