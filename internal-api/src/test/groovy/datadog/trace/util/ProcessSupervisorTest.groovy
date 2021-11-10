package datadog.trace.util

import datadog.trace.test.util.DDSpecification
import spock.lang.Requires
import spock.util.concurrent.PollingConditions

// This test looks at the private "currentProcess" variable because the alternative
// would be calling "ps -e" repeatedly
@Requires({ jvm.java8Compatible })
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
      processSupervisor.currentProcess != null
      processSupervisor.currentProcess.isAlive()
    }

    when:
    def oldProcess = processSupervisor.currentProcess
    processSupervisor.close()

    then:
    conditions.eventually {
      !oldProcess.isAlive()
      processSupervisor.currentProcess == null || !processSupervisor.currentProcess.isAlive()
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
      processSupervisor.currentProcess != null
      processSupervisor.currentProcess.isAlive()
    }

    when:
    def oldProcess = processSupervisor.currentProcess
    oldProcess.destroyForcibly()

    then:
    conditions.eventually {
      !oldProcess.isAlive()
      processSupervisor.currentProcess != null
      processSupervisor.currentProcess != oldProcess
      processSupervisor.currentProcess.isAlive()
    }

    cleanup:
    processSupervisor.close()
  }
}
