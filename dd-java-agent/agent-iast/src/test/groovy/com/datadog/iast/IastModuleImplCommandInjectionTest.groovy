package com.datadog.iast

import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import spock.lang.Shared

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class IastModuleImplCommandInjectionTest extends IastModuleImplTestBase {

  @Shared
  private List<Object> objectHolder

  @Shared
  private IastRequestContext ctx

  def setup() {
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
    overheadController.consumeQuota(_, _) >> true
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
