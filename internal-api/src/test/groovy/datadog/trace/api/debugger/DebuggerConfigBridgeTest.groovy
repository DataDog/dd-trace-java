package datadog.trace.api.debugger

import datadog.environment.JavaVirtualMachine
import datadog.trace.api.InstrumenterConfig
import spock.lang.Specification

class DebuggerConfigBridgeTest extends Specification {

  def cleanup() {
    DebuggerConfigBridge.reset()
  }

  def "test bridge handles calls"() {
    when:
    def updater = new MockDebuggerConfigUpdater()

    then:
    !DebuggerConfigBridge.isDynamicInstrumentationEnabled()
    !DebuggerConfigBridge.isExceptionReplayEnabled()
    DebuggerConfigBridge.isCodeOriginEnabled() == InstrumenterConfig.defaultCodeOriginForSpanEnabled
    !DebuggerConfigBridge.isDistributedDebuggerEnabled()

    when:
    DebuggerConfigBridge.setUpdater(updater)
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(null, true, null, null))

    then:
    updater.calls == 1
    DebuggerConfigBridge.isExceptionReplayEnabled()

    when:
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(true, null, null, null))

    then:
    updater.calls == 2
    DebuggerConfigBridge.isExceptionReplayEnabled()
    DebuggerConfigBridge.isDynamicInstrumentationEnabled()

    when:
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(false, false, false, false))

    then:
    updater.calls == 3
    !DebuggerConfigBridge.isDynamicInstrumentationEnabled()
    !DebuggerConfigBridge.isExceptionReplayEnabled()
    !DebuggerConfigBridge.isCodeOriginEnabled()
    !DebuggerConfigBridge.isDistributedDebuggerEnabled()
  }

  def "test bridge ignores empty updates"() {
    def updater = new MockDebuggerConfigUpdater()
    DebuggerConfigBridge.setUpdater(updater)

    when:
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate())

    then:
    updater.calls == 0
  }

  def "test bridge handles deferred updates"() {
    def updater = new MockDebuggerConfigUpdater()

    when:
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(null, false, null, null))
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(true, true, true, true))
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(null, false, null, null))

    then:
    updater.calls == 0

    when:
    DebuggerConfigBridge.setUpdater(updater)

    then:
    updater.calls == 1
    DebuggerConfigBridge.isDynamicInstrumentationEnabled()
    !DebuggerConfigBridge.isExceptionReplayEnabled()
    DebuggerConfigBridge.isCodeOriginEnabled()
    DebuggerConfigBridge.isDistributedDebuggerEnabled()
  }

  private static class MockDebuggerConfigUpdater implements DebuggerConfigUpdater {
    private int calls = 0
    private boolean di
    private boolean er
    private boolean co
    private boolean dd

    @Override
    void updateConfig(DebuggerConfigUpdate update) {
      calls++
      di = update.getDynamicInstrumentationEnabled() != null ? update.getDynamicInstrumentationEnabled() : di
      er = update.getExceptionReplayEnabled() != null ? update.getExceptionReplayEnabled() : er
      co = update.getCodeOriginEnabled() != null ? update.getCodeOriginEnabled() : co
      dd = update.getDistributedDebuggerEnabled() != null ? update.getDistributedDebuggerEnabled() : dd
    }

    @Override
    boolean isDynamicInstrumentationEnabled() {
      return di
    }

    @Override
    boolean isExceptionReplayEnabled() {
      return er
    }

    @Override
    boolean isCodeOriginEnabled() {
      return co
    }

    @Override
    boolean isDistributedDebuggerEnabled() {
      return dd
    }
  }
}
