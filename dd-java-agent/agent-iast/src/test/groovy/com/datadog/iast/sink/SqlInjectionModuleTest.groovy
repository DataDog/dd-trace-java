package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.sink.SqlInjectionModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.model.Range.NOT_MARKED
import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat
import static datadog.trace.api.iast.sink.SqlInjectionModule.DATABASE_PARAMETER

class SqlInjectionModuleTest extends IastModuleImplTestBase {

  private SqlInjectionModule module

  private List<Object> objectHolder

  private IastRequestContext ctx

  def setup() {
    module = registerDependencies(new SqlInjectionModuleImpl())
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

  void 'jdbc report a vulnerability if the string is tainted and is not marked as sqli'(final String queryString, final int mark, final String database, final String expected) {
    given:
    Vulnerability savedVul
    final param = mapTainted(queryString, mark)

    when:
    if (database == null) {
      module.onJdbcQuery(param)
    } else {
      module.onJdbcQuery(param, database)
    }

    then:
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertEvidence(savedVul, expected, database)

    where:
    queryString  | mark                                    | database | expected
    '/==>var<==' | NOT_MARKED                              | null     | "/==>var<=="
    '/==>var<==' | NOT_MARKED                              | 'h2'     | "/==>var<=="
    '/==>var<==' | VulnerabilityMarks.XPATH_INJECTION_MARK | null     | "/==>var<=="
  }

  void 'jdbc report a vulnerability if the string is not tainted or is marked for sqli'(final String queryString, final int mark, final String database) {
    given:
    final param = mapTainted(queryString, mark)

    when:
    if (database == null) {
      module.onJdbcQuery(param)
    } else {
      module.onJdbcQuery(param, database)
    }

    then:
    0 * reporter.report(_, _)

    where:
    queryString  | mark                                  | database
    null         | NOT_MARKED                            | null
    '/var'       | NOT_MARKED                            | null
    '/==>var<==' | VulnerabilityMarks.SQL_INJECTION_MARK | null
    null         | NOT_MARKED                            | 'h2'
    '/var'       | NOT_MARKED                            | 'h2'
    '/==>var<==' | VulnerabilityMarks.SQL_INJECTION_MARK | 'h2'
  }


  private String mapTainted(final String value, final int mark) {
    final result = addFromTaintFormat(ctx.taintedObjects, value, mark)
    objectHolder.add(result)
    return result
  }

  private static void assertVulnerability(final Vulnerability vuln) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.SQL_INJECTION
    assert vuln.getLocation() != null
  }

  private static void assertEvidence(final Vulnerability vuln, final String expected, final String database) {
    assertVulnerability(vuln)
    final evidence = vuln.getEvidence()
    assert evidence != null
    final formatted = taintFormat(evidence.getValue(), evidence.getRanges())
    assert formatted == expected
    assert evidence.getContext().get(DATABASE_PARAMETER) == database
  }
}

