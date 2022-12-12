package com.datadog.iast

import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import spock.lang.Shared

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class IastModuleLDAPInjectionTest extends IastModuleImplTestBase {

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

  void 'iast module detect LDAP injection on search(#name, #filter, #args)'(final String name, final String filter, final List<Object> args, final String expected) {
    setup:
    final taintedName = addFromTaintFormat(ctx.taintedObjects, name)
    objectHolder.add(taintedName)
    final taintedFilter = addFromTaintFormat(ctx.taintedObjects, filter)
    objectHolder.add(taintedName)
    final mapedArgs = mapTaintedElement(args).toArray(new Object[0]) as Object[]

    when:
    module.onDirContextSearch(taintedName, taintedFilter, mapedArgs)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { elements -> assertVulnerability(elements[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    name         | filter                                 | args                         | expected
    'name'       | '(&(uid={0})(userPassword={1}))'       | ['arg1', 'arg2']             | null
    null         | '(&(uid={0})(userPassword={1}))'       | ['arg1', 'arg2']             | null
    'name'       | null                                   | ['arg1', 'arg2']             | null
    'name'       | '(&(uid=arg1)(userPassword=arg2))'     | null                         | null
    'name'       | '(&(uid={0})(userPassword={1}))'       | ['arg1', null]               | null
    '==>name<==' | '(&(uid={0})(userPassword={1}))'       | ['arg1', 'arg2']             | '==>name<== (&(uid={0})(userPassword={1})) arg1 arg2'
    'na==>m<==e' | '(&(uid={0})(userPassword={1}))'       | ['arg1', 'arg2']             | 'na==>m<==e (&(uid={0})(userPassword={1})) arg1 arg2'
    'na==>m<==e' | '(&(==>uid<==={0})(userPassword={1}))' | ['arg1', 'arg2']             | 'na==>m<==e (&(==>uid<==={0})(userPassword={1})) arg1 arg2'
    'na==>m<==e' | '(&(==>uid<==={0})(userPassword={1}))' | ['a==>r<==g1', 'arg2']       | 'na==>m<==e (&(==>uid<==={0})(userPassword={1})) a==>r<==g1 arg2'
    'na==>m<==e' | '(&(==>uid<==={0})(userPassword={1}))' | [new Object(), 'a==>r<==g2'] | 'na==>m<==e (&(==>uid<==={0})(userPassword={1})) a==>r<==g2'
    'na==>m<==e' | '(&(==>uid<==={0})(userPassword={1}))' | [new Object(), null]         | 'na==>m<==e (&(==>uid<==={0})(userPassword={1}))'
  }


  private List<Object> mapTaintedElement(final List<Object> element) {
    element.collect {
      if (it instanceof String) {
        final item = addFromTaintFormat(ctx.taintedObjects, it)
        objectHolder.add(item)
        return item
      }
      return it
    }
  }

  private static void assertVulnerability(final Vulnerability vuln, final String expected) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.LDAP_INJECTION
    assert vuln.getLocation() != null
    final evidence = vuln.getEvidence()
    assert evidence != null
    final formatted = taintFormat(evidence.getValue(), evidence.getRanges())
    assert formatted == expected
  }
}
