package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.sink.SqlInjectionModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.test.IastAgentTestRunner.EMPTY_SOURCE
import static datadog.trace.api.iast.sink.SqlInjectionModule.DATABASE_PARAMETER

class SqlInjectionModuleTest extends IastModuleImplTestBase {

  private SqlInjectionModule module

  def setup() {
    module = registerDependencies(new SqlInjectionModuleImpl())
  }

  void 'jdbc report a vulnerability if the string is tainted'() {
    given:
    Vulnerability savedVul
    def iastRC = new IastRequestContext()
    final span = Mock(AgentSpan) {
      getRequestContext() >> Mock(RequestContext) {
        getData(RequestContextSlot.IAST) >> iastRC
      }
    }

    when:
    iastRC.taintedObjects.taintInputString(queryString, EMPTY_SOURCE)
    if (database) {
      module.onJdbcQuery(queryString, database)
    } else {
      module.onJdbcQuery(queryString)
    }

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    with(savedVul) {
      type == VulnerabilityType.SQL_INJECTION
      location != null
      with(evidence) {
        value == queryString
        ranges.length == 1
        context.get(DATABASE_PARAMETER) == database
        with(ranges[0]) {
          start == 0
          length == 11
          source == EMPTY_SOURCE
        }
      }
    }

    where:
    queryString   | database
    'dummy query' | null
    'dummy query' | 'h2'
  }

  void 'nothing is reported if the query is not tainted'(final String queryString) {
    given:
    def iastRC = new IastRequestContext()
    final span = Mock(AgentSpan) {
      getRequestContext() >> Mock(RequestContext) {
        getData(RequestContextSlot.IAST) >> iastRC
      }
    }

    when:
    if (database) {
      module.onJdbcQuery(queryString, database)
    } else {
      module.onJdbcQuery(queryString)
    }

    then:
    if (queryString) {
      1 * tracer.activeSpan() >> span
    } else {
      0 * tracer.activeSpan()
    }
    0 * overheadController._
    0 * reporter._

    where:
    queryString   | database
    null          | null
    'dummy query' | null
    null          | 'h2'
    'dummy query' | 'h2'
  }
}
