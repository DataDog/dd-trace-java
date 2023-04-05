package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.sink.CommandInjectionModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import groovy.transform.CompileDynamic

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

@CompileDynamic
class CommandInjectionModuleTest extends IastModuleImplTestBase {

  private CommandInjectionModule module

  private List<Object> objectHolder

  private IastRequestContext ctx

  private AgentSpan span

  def setup() {
    module = registerDependencies(new CommandInjectionModuleImpl())
    objectHolder = []
    ctx = new IastRequestContext()
    final reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
    }
    span = Mock(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
  }

  void 'iast module detect command injection on process builder start (#command)'() {
    setup:
    final cmd = mapTaintedArray(command)

    when:
    module.onProcessBuilderStart(cmd)

    then:
    tracer.activeSpan() >> span
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    when:
    module.onProcessBuilderStart(cmd)

    then:
    tracer.activeSpan() >> null
    0 * reporter.report(_, _)

    where:
    command                    | expected
    null                       | null
    []                         | null
    ['ls', '-lah']             | null
    ['==>ls<==', '-lah']       | '==>ls<== -lah'
    ['==>ls<==', '==>-lah<=='] | '==>ls<== ==>-lah<=='
  }

  void 'iast module detect command injection on runtime exec (#command)'() {
    setup:
    final cmd = mapTaintedArray(command).toArray(new String[0]) as String[]

    when:
    module.onRuntimeExec(cmd)

    then:
    tracer.activeSpan() >> span
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    when:
    module.onRuntimeExec(cmd)

    then:
    tracer.activeSpan() >> null
    0 * reporter.report(_, _)

    where:
    command                    | expected
    null                       | null
    []                         | null
    ['ls', '-lah']             | null
    ['==>ls<==', '-lah']       | '==>ls<== -lah'
    ['==>ls<==', '==>-lah<=='] | '==>ls<== ==>-lah<=='
  }

  void 'iast module detect command injection on runtime exec (#env)'() {
    setup:
    final cmd = mapTaintedArray(command).toArray(new String[0]) as String[]
    final environment = mapTaintedArray(env).toArray(new String[0]) as String[]

    when:
    module.onRuntimeExec(environment, cmd)

    then:
    tracer.activeSpan() >> span
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    when:
    module.onRuntimeExec(environment, cmd)

    then:
    tracer.activeSpan() >> null
    0 * reporter.report(_, _)

    where:
    command              | env                                                             | expected
    null                 | null                                                            | null
    ['ls', '-lah']       | null                                                            | null
    ['ls', '-lah']       | []                                                              | null
    ['ls', '-lah']       | ['HAS_VULNERABILITY=false', 'JAVA_HOME=/home/java']             | null
    ['ls', '-lah']       | ['HAS_VULNERABILITY=false', 'JAVA_HOME===>/home/java<==']       | 'HAS_VULNERABILITY=false JAVA_HOME===>/home/java<== ls -lah'
    ['ls', '-lah']       | ['HAS_VULNERABILITY===>false<==', 'JAVA_HOME===>/home/java<=='] | 'HAS_VULNERABILITY===>false<== JAVA_HOME===>/home/java<== ls -lah'
    ['==>ls<==', '-lah'] | ['HAS_VULNERABILITY===>false<==', 'JAVA_HOME===>/home/java<=='] | 'HAS_VULNERABILITY===>false<== JAVA_HOME===>/home/java<== ==>ls<== -lah'
  }

  private List<String> mapTaintedArray(final List<String> array) {
    return array.collect {
      final item = addFromTaintFormat(ctx.taintedObjects, it)
      objectHolder.add(item)
      return item
    }
  }

  private static void assertVulnerability(final Vulnerability vuln, final String expected) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.COMMAND_INJECTION
    assert vuln.getLocation() != null
    final evidence = vuln.getEvidence()
    assert evidence != null
    final formatted = taintFormat(evidence.getValue(), evidence.getRanges())
    assert formatted == expected
  }
}
