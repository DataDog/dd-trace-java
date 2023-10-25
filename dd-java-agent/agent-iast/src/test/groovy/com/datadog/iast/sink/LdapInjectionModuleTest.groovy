package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.sink.LdapInjectionModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import groovy.transform.CompileDynamic

import static com.datadog.iast.model.Range.NOT_MARKED
import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

@CompileDynamic
class LdapInjectionModuleTest extends IastModuleImplTestBase {

  private List<Object> objectHolder

  private IastRequestContext ctx

  private LdapInjectionModule module

  private AgentSpan span

  def setup() {
    module = registerDependencies(new LdapInjectionModuleImpl())
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

  void 'iast module detect LDAP injection on search(#name, #filter, #args, #mark)'(final String name, final String filter, final List<Object> args, final int mark, final String expected) {
    setup:
    final taintedName = addFromTaintFormat(ctx.taintedObjects, name, mark)
    objectHolder.add(taintedName)
    final taintedFilter = addFromTaintFormat(ctx.taintedObjects, filter, mark)
    objectHolder.add(taintedName)
    final mapedArgs = mapTaintedElement(args, mark).toArray(new Object[0]) as Object[]

    when:
    module.onDirContextSearch(taintedName, taintedFilter, mapedArgs)

    then:
    tracer.activeSpan() >> span
    if (expected != null) {
      1 * reporter.report(_, _) >> { elements -> assertVulnerability(elements[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    when:
    module.onDirContextSearch(taintedName, taintedFilter, mapedArgs)

    then:
    tracer.activeSpan() >> null
    0 * reporter.report(_, _)

    where:
    name         | filter                                 | args                   | mark                                   | expected
    'na==>m<==e' | '(&(==>uid<==={0})(userPassword={1}))' | ['a==>r<==g1', 'arg2'] | VulnerabilityMarks.LDAP_INJECTION_MARK | null
    ''           | ''                                     | []                     | NOT_MARKED                             | null
    'name'       | '(&(uid={0})(userPassword={1}))'       | ['arg1', 'arg2']       | NOT_MARKED                             | null
    null         | '(&(uid={0})(userPassword={1}))'       | ['arg1', 'arg2']       | NOT_MARKED                             | null
    'name'       | null                                   | ['arg1', 'arg2']       | NOT_MARKED                             | null
    'name'       | '(&(uid=arg1)(userPassword=arg2))'     | null                   | NOT_MARKED                             | null
    'name'       | '(&(uid={0})(userPassword={1}))'       | ['arg1', null]         | NOT_MARKED                             | null
    '==>name<==' | '(&(uid={0})(userPassword={1}))'       | ['arg1', 'arg2']       | NOT_MARKED                             | '==>name<== (&(uid={0})(userPassword={1})) arg1 arg2'
    'na==>m<==e' | '(&(uid={0})(userPassword={1}))'       | ['arg1', 'arg2']       | NOT_MARKED                             | 'na==>m<==e (&(uid={0})(userPassword={1})) arg1 arg2'
    'na==>m<==e' | '(&(==>uid<==={0})(userPassword={1}))' | ['arg1', 'arg2']       | NOT_MARKED                             | 'na==>m<==e (&(==>uid<==={0})(userPassword={1})) arg1 arg2'
    'na==>m<==e' | '(&(==>uid<==={0})(userPassword={1}))' | ['a==>r<==g1', 'arg2'] | NOT_MARKED                             | 'na==>m<==e (&(==>uid<==={0})(userPassword={1})) a==>r<==g1 arg2'
    'na==>m<==e' | '(&(==>uid<==={0})(userPassword={1}))' | [23L, 'a==>r<==g2']    | NOT_MARKED                             | 'na==>m<==e (&(==>uid<==={0})(userPassword={1})) 23 a==>r<==g2'
    'na==>m<==e' | '(&(==>uid<==={0})(userPassword={1}))' | [23L, null]            | NOT_MARKED                             | 'na==>m<==e (&(==>uid<==={0})(userPassword={1})) 23'
    'na==>m<==e' | '(&(==>uid<==={0})(userPassword={1}))' | ['a==>r<==g1', 'arg2'] | VulnerabilityMarks.SQL_INJECTION_MARK  | 'na==>m<==e (&(==>uid<==={0})(userPassword={1})) a==>r<==g1 arg2'
  }


  private List<Object> mapTaintedElement(final List<Object> element, final int mark) {
    element.collect {
      if (it instanceof String) {
        final item = addFromTaintFormat(ctx.taintedObjects, it, mark)
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
