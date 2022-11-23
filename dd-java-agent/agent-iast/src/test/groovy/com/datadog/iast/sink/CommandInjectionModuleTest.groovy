package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.sink.CommandInjectionModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class CommandInjectionModuleTest extends IastModuleImplTestBase {

  private CommandInjectionModule module

  private List<Object> objectHolder

  private IastRequestContext ctx

  def setup() {
    module = registerDependencies(new CommandInjectionModuleImpl())
    objectHolder = []
    ctx = new IastRequestContext()
    final reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
    }
    final span = Mock(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
    tracer.activeSpan() >> span
  }

  void 'iast module detect command injection on process builder start (#command)'(final List<String> command, final String expected) {
    setup:
    final cmd = mapTaintedCommand(command)

    when:
    module.onProcessBuilderStart(cmd)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    command                    | expected
    null                       | null
    []                         | null
    ['ls', '-lah']             | null
    ['==>ls<==', '-lah']       | '==>ls<== -lah'
    ['==>ls<==', '==>-lah<=='] | '==>ls<== ==>-lah<=='
  }

  void 'iast module detect command injection on runtime exec (#command)'(final List<String> command, final String expected) {
    setup:
    final cmd = mapTaintedCommand(command).toArray(new String[0]) as String[]

    when:
    module.onRuntimeExec(cmd)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    command                    | expected
    null                       | null
    []                         | null
    ['ls', '-lah']             | null
    ['==>ls<==', '-lah']       | '==>ls<== -lah'
    ['==>ls<==', '==>-lah<=='] | '==>ls<== ==>-lah<=='
  }

  private List<String> mapTaintedCommand(final List<String> command) {
    return command.collect {
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
