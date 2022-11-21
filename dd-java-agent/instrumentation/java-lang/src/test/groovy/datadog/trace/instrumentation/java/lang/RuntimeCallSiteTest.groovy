package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge
import foo.bar.TestRuntimeSuite

class RuntimeCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test exec with command string'() {
    setup:
    final runtime = Mock(Runtime)
    final iastModule = Mock(IastModule)
    final command = 'ls'
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    new TestRuntimeSuite(runtime).exec(command)

    then:
    1 * iastModule.onRuntimeExec(command)
    1 * runtime.exec(command)
    0 * _
  }

  def 'test exec with command string and env array'() {
    setup:
    final runtime = Mock(Runtime)
    final iastModule = Mock(IastModule)
    final command = 'ls'
    final env = ['DD_TRACE_DEBUG=true'] as String[]
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    new TestRuntimeSuite(runtime).exec(command, env)

    then:
    1 * iastModule.onRuntimeExec(command)
    1 * runtime.exec(command, env)
    0 * _
  }

  def 'test exec with command string array'() {
    setup:
    final runtime = Mock(Runtime)
    final iastModule = Mock(IastModule)
    final command = ['ls', '-lah'] as String[]
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    new TestRuntimeSuite(runtime).exec(command)

    then:
    1 * iastModule.onRuntimeExec(command)
    1 * runtime.exec(command)
    0 * _
  }

  def 'test exec with command string array and env array'() {
    setup:
    final runtime = Mock(Runtime)
    final iastModule = Mock(IastModule)
    final command = ['ls', '-lah'] as String[]
    final env = ['DD_TRACE_DEBUG=true'] as String[]
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    new TestRuntimeSuite(runtime).exec(command, env)

    then:
    1 * iastModule.onRuntimeExec(command)
    1 * runtime.exec(command, env)
    0 * _
  }

  def 'test exec with command string and env array and dir'() {
    setup:
    final runtime = Mock(Runtime)
    final iastModule = Mock(IastModule)
    final command = 'ls'
    final env = ['DD_TRACE_DEBUG=true'] as String[]
    final file = Mock(File)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    new TestRuntimeSuite(runtime).exec(command, env, file)

    then:
    1 * iastModule.onRuntimeExec(command)
    1 * runtime.exec(command, env, file)
    0 * _
  }

  def 'test exec with command string array and env array and dir'() {
    setup:
    final runtime = Mock(Runtime)
    final iastModule = Mock(IastModule)
    final command = ['ls', '-lah'] as String[]
    final env = ['DD_TRACE_DEBUG=true'] as String[]
    final file = Mock(File)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    new TestRuntimeSuite(runtime).exec(command, env, file)

    then:
    1 * iastModule.onRuntimeExec(command)
    1 * runtime.exec(command, env, file)
    0 * _
  }
}
