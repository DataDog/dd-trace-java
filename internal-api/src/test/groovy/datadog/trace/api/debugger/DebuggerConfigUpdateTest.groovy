package datadog.trace.api.debugger

import spock.lang.Specification

class DebuggerConfigUpdateTest extends Specification {

  def "test update coalesce"() {
    def existing = new DebuggerConfigUpdate.Builder().setExceptionReplayEnabled(true).setDynamicInstrumentationEnabled(false).build()
    def update = new DebuggerConfigUpdate.Builder().setExceptionReplayEnabled(false).setCodeOriginEnabled(true).build()

    when:
    def result = DebuggerConfigUpdate.coalesce(existing, update)

    then:
    !result.getDynamicInstrumentationEnabled()
    !result.getExceptionReplayEnabled()
    result.getCodeOriginEnabled()
    result.getDistributedDebuggerEnabled() == null
  }
}
