package datadog.trace.api.debugger

import spock.lang.Specification

class DebuggerConfigUpdateTest extends Specification {

  def "test update coalesce"() {
    def existing = new DebuggerConfigUpdate(false, true, null, null)
    def update = new DebuggerConfigUpdate(null, false, true, null)

    when:
    def result = DebuggerConfigUpdate.coalesce(existing, update)

    then:
    !result.getDynamicInstrumentationEnabled()
    !result.getExceptionReplayEnabled()
    result.getCodeOriginEnabled()
    result.getDistributedDebuggerEnabled() == null
  }
}
