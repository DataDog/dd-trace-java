package test

import static java.util.Collections.singletonList

import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.Config
import org.apache.ignite.Ignite
import org.apache.ignite.Ignition
import org.apache.ignite.configuration.IgniteConfiguration
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder
import spock.lang.Shared

abstract class IgniteBaseTest extends VersionedNamingTestBase {
  static final String V0_SERVICE = "ignite"
  static final String V1_SERVICE = Config.get().getServiceName()
  static final String V0_OPERATION = "ignite.cache"
  static final String V1_OPERATION = "ignite.command"
  static final String LOOPBACK = "127.0.0.1"

  @Shared
  Ignite igniteServer, igniteClient

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.ignite.enabled", "true")
  }

  protected void startIgnite(Closure<?> customizeClient = {}) {
    int discoveryPort = PortUtils.randomOpenPort()
    TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder().setAddresses(singletonList(LOOPBACK + ":" + discoveryPort))

    IgniteConfiguration serverConfig = new IgniteConfiguration()
    serverConfig.setWorkDirectory(File.createTempDir("ignite-server-").toString())
    serverConfig.setIgniteInstanceName("igniteServer")
    // The client knows only this endpoint, so the server must bind to that exact port.
    serverConfig.setDiscoverySpi(new TcpDiscoverySpi()
      .setLocalAddress(LOOPBACK)
      .setLocalPort(discoveryPort)
      .setLocalPortRange(0)
      .setIpFinder(ipFinder))
    igniteServer = Ignition.start(serverConfig)

    IgniteConfiguration clientConfig = new IgniteConfiguration()
    clientConfig.setWorkDirectory(File.createTempDir("ignite-client-").toString())
    clientConfig.setIgniteInstanceName("igniteClient")
    clientConfig.setClientMode(true)
    clientConfig.setDiscoverySpi(new TcpDiscoverySpi().setLocalAddress(LOOPBACK).setIpFinder(ipFinder))
    customizeClient(clientConfig)
    igniteClient = Ignition.start(clientConfig)
  }

  protected void stopIgnite() {
    try {
      igniteClient?.close()
    } finally {
      igniteServer?.close()
    }
  }
}
