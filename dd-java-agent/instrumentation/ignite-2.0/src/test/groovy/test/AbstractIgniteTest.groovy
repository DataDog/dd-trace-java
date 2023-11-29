package test


import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.apache.ignite.Ignite
import org.apache.ignite.Ignition
import org.apache.ignite.configuration.IgniteConfiguration
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder
import spock.lang.Shared

abstract class AbstractIgniteTest extends VersionedNamingTestBase {
  static final String V0_SERVICE = "ignite"
  static final String V1_SERVICE = Config.get().getServiceName()
  static final String V0_OPERATION = "ignite.cache"
  static final String V1_OPERATION = "ignite.command"

  @Shared
  Ignite igniteServer, igniteClient

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.ignite.enabled", "true")
  }

  def setupSpec() {
    IgniteConfiguration serverConfig = new IgniteConfiguration()
    IgniteConfiguration clientConfig = new IgniteConfiguration()

    // Need temporary directories for the files that Ignite creates
    serverConfig.setWorkDirectory(
      File.createTempDir("igniteserver", UUID.randomUUID().toString()).toString())
    clientConfig.setWorkDirectory(
      File.createTempDir("igniteclient", UUID.randomUUID().toString()).toString())

    serverConfig.setIgniteInstanceName("igniteServer")
    clientConfig.setIgniteInstanceName("igniteClient")

    clientConfig.setClientMode(true)

    TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder()
    ipFinder.setAddresses(Collections.singletonList("127.0.0.1:47500..47509"))
    serverConfig.setDiscoverySpi(new TcpDiscoverySpi().setIpFinder(ipFinder))
    clientConfig.setDiscoverySpi(new TcpDiscoverySpi().setIpFinder(ipFinder))

    igniteServer = Ignition.start(serverConfig)
    igniteClient = Ignition.start(clientConfig)
  }

  def cleanupSpec() {
    igniteClient?.close()
    igniteServer?.close()
  }

  void assertIgniteCall(TraceAssert trace, String name, String cacheName, boolean parentSpan = true) {
    trace.span {
      serviceName service()
      resourceName name + " on " + cacheName
      operationName operation()
      spanType DDSpanTypes.CACHE
      errored false
      if (parentSpan) {
        parent()
      } else {
        childOfPrevious()
      }
      tags {
        "$Tags.COMPONENT" "ignite-cache"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.DB_TYPE" "ignite"
        "ignite.operation" name
        "ignite.cache.name" cacheName
        if (igniteClient.name()) {
          "ignite.instance" igniteClient.name()
        }
        "ignite.version" igniteClient.version().toString()
        defaultTagsNoPeerService()
      }
    }
  }
}
