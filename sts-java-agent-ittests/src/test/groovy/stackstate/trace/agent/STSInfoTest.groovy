package stackstate.trace.agent

import stackstate.opentracing.STSTraceOTInfo
import stackstate.trace.api.STSTraceApiInfo

class STSInfoTest {
  def "info accessible from api"() {
    expect:
    STSTraceApiInfo.VERSION == STSTraceOTInfo.VERSION

    STSTraceApiInfo.VERSION != null
    STSTraceApiInfo.VERSION != ""
    STSTraceApiInfo.VERSION != "unknown"
    STSTraceOTInfo.VERSION != null
    STSTraceOTInfo.VERSION != ""
    STSTraceOTInfo.VERSION != "unknown"
  }

  def "info accessible from agent"() {
    setup:
    def clazz = Class.forName("stackstate.trace.agent.tooling.STSJavaAgentInfo")
    def versionField = clazz.getDeclaredField("VERSION")
    def version = versionField.get(null)

    expect:
    version != null
    version != ""
    version != "unknown"
    version == STSTraceApiInfo.VERSION
  }
}
