package datadog.trace.agent


import datadog.trace.core.DDTraceCoreInfo
import datadog.trace.api.DDTraceApiInfo
import spock.lang.Specification

class DDInfoTest extends Specification {
  def "info accessible from api"() {
    expect:
    DDTraceApiInfo.VERSION == DDTraceCoreInfo.VERSION

    DDTraceApiInfo.VERSION != null
    DDTraceApiInfo.VERSION != ""
    DDTraceApiInfo.VERSION != "unknown"
    DDTraceCoreInfo.VERSION != null
    DDTraceCoreInfo.VERSION != ""
    DDTraceCoreInfo.VERSION != "unknown"
  }
}
