package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.CommandInjectionModule
import foo.bar.TestRuntimeSuite
import groovy.transform.CompileDynamic

@CompileDynamic
class RuntimeCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test exec with command string'() {
    setup:
    System.out.println(System.getProperty("java.version"))
    final runtime = Mock(Runtime)
    CommandInjectionModule iastModule = Mock(CommandInjectionModule)
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
    CommandInjectionModule iastModule = Mock(CommandInjectionModule)
    final command = 'ls'
    final env = ['DD_TRACE_DEBUG=true'] as String[]
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    new TestRuntimeSuite(runtime).exec(command, env)

    then:
    1 * iastModule.onRuntimeExec(env, command)
    1 * runtime.exec(command, env)
    0 * _
  }

  def 'test exec with command string array'() {
    setup:
    final runtime = Mock(Runtime)
    CommandInjectionModule iastModule = Mock(CommandInjectionModule)
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
    CommandInjectionModule iastModule = Mock(CommandInjectionModule)
    final command = ['ls', '-lah'] as String[]
    final env = ['DD_TRACE_DEBUG=true'] as String[]
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    new TestRuntimeSuite(runtime).exec(command, env)

    then:
    1 * iastModule.onRuntimeExec(env, command)
    1 * runtime.exec(command, env)
    0 * _
  }

  def 'test exec with command string and env array and dir'() {
    setup:
    final runtime = Mock(Runtime)
    CommandInjectionModule iastModule = Mock(CommandInjectionModule)
    final command = 'ls'
    final env = ['DD_TRACE_DEBUG=true'] as String[]
    final file = Mock(File)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    new TestRuntimeSuite(runtime).exec(command, env, file)

    then:
    1 * iastModule.onRuntimeExec(env, command)
    1 * runtime.exec(command, env, file)
    0 * _
  }

  def 'test exec with command string array and env array and dir'() {
    setup:
    final runtime = Mock(Runtime)
    CommandInjectionModule iastModule = Mock(CommandInjectionModule)
    final command = ['ls', '-lah'] as String[]
    final env = ['DD_TRACE_DEBUG=true'] as String[]
    final file = Mock(File)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    new TestRuntimeSuite(runtime).exec(command, env, file)

    then:
    1 * iastModule.onRuntimeExec(env, command)
    1 * runtime.exec(command, env, file)
    0 * _
  }
}
