package datadog.flare

import static datadog.trace.api.config.GeneralConfig.TELEMETRY_JMX_ENABLED

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification
import okhttp3.HttpUrl
import spock.lang.Timeout

import javax.management.MBeanServer
import javax.management.ObjectName
import java.lang.management.ManagementFactory

@Timeout(1)
class TracerFlareJmxTest extends DDSpecification {
  static final ObjectName MBEAN_NAME = new ObjectName("datadog.flare:type=TracerFlare")

  final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer()

  TracerFlareService tracerFlareService

  def cleanup() {
    if (tracerFlareService != null) {
      tracerFlareService.close()
    }
  }

  private void createTracerFlareService() {
    tracerFlareService = new TracerFlareService(
      Config.get(),
      null, // okHttpClient - not needed for JMX test
      HttpUrl.get("http://localhost:8126")
      )
  }

  def "TracerFlare MBean is registered when telemetry JMX is enabled"() {
    given:
    injectSysConfig(TELEMETRY_JMX_ENABLED, "true")

    when:
    createTracerFlareService()

    then:
    mbs.isRegistered(MBEAN_NAME)
  }

  def "TracerFlare MBean is not registered when telemetry JMX is disabled"() {
    given:
    injectSysConfig(TELEMETRY_JMX_ENABLED, "false")

    when:
    createTracerFlareService()

    then:
    !mbs.isRegistered(MBEAN_NAME)
  }

  def "TracerFlare MBean operations work when JMX is enabled"() {
    given:
    injectSysConfig(TELEMETRY_JMX_ENABLED, "true")
    createTracerFlareService()

    when:
    def fileList = mbs.invoke(MBEAN_NAME, "listFlareFiles", null, null) as String

    then:
    mbs.isRegistered(MBEAN_NAME)
    fileList != null
    fileList.contains("flare_info.txt")
    fileList.contains("tracer_version.txt")
    fileList.contains("initial_config.txt")
  }
}