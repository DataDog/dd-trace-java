package datadog.trace.api.debugger

import spock.lang.Specification

class DebuggerConfigBridgeTest extends Specification {

  def "test bridge update calls"() {
    def updater = new MockDebuggerConfigUpdater()

    when:
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate.Builder().setExceptionReplayEnabled(true).build())

    then:
    updater.calls == 0

    when:
    DebuggerConfigBridge.setUpdater(updater)

    then:
    updater.calls == 1 // deferred updates are done on set

    when:
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate.Builder().setExceptionReplayEnabled(false).build())

    then:
    updater.calls == 2
  }

  private static class MockDebuggerConfigUpdater implements DebuggerConfigUpdater {
    private int calls = 0

    @Override
    void updateConfig(DebuggerConfigUpdate update) {
      calls++
    }

    @Override
    boolean isDynamicInstrumentationEnabled() {
      return false
    }

    @Override
    boolean isExceptionReplayEnabled() {
      return false
    }

    @Override
    boolean isCodeOriginEnabled() {
      return false
    }

    @Override
    boolean isDistributedDebuggerEnabled() {
      return false
    }
  }
}
