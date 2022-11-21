package com.datadog.iast


import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.IastAgentTestRunner.EMPTY_SOURCE

class IastModuleImplJdbcQueryTest extends IastModuleImplTestBase {

  void 'jdbc report a vulnerability iff the string is tainted'() {
    given:
    Vulnerability savedVul
    def iastRC = new IastRequestContext()
    final span = Mock(AgentSpan) {
      getRequestContext() >> Mock(RequestContext) {
        getData(RequestContextSlot.IAST) >> iastRC
      }
    }
    String queryString = 'dummy query'

    when:
    iastRC.taintedObjects.taintInputString(queryString, EMPTY_SOURCE)
    module.onJdbcQuery(queryString)

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    with(savedVul) {
      type == VulnerabilityType.SQL_INJECTION
      location != null
      with(evidence) {
        value == 'dummy query'
        ranges.length == 1
        with(ranges[0]) {
          start == 0
          length == 11
          source == EMPTY_SOURCE
        }
      }
    }
  }

  void 'nothing is reported if the query is not tainted'() {
    given:
    def iastRC = new IastRequestContext()
    final span = Mock(AgentSpan) {
      getRequestContext() >> Mock(RequestContext) {
        getData(RequestContextSlot.IAST) >> iastRC
      }
    }
    String queryString = 'dummy query'

    when:
    module.onJdbcQuery(queryString)

    then:
    1 * tracer.activeSpan() >> span
    0 * overheadController._
    0 * reporter._
  }
}
