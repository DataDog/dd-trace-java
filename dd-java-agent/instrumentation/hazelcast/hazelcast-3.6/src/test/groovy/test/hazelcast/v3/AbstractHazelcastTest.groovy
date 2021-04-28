package test.hazelcast.v3

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import net.bytebuddy.utility.RandomString
import spock.lang.Shared

abstract class AbstractHazelcastTest extends AgentTestRunner {

  @Shared HazelcastInstance h1, client
  @Shared String randomName

  final resourceNamePattern = ~/^(?<name>(?<service>\w+)\[[^]]+\])\.(?<operation>\w+)$/

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.hazelcast_legacy.enabled", "true")
  }

  @Override
  def setupSpec() {
    def port = PortUtils.randomOpenPort()
    def groupName = RandomString.make(8)
    def groupPassword = RandomString.make(8)

    def serverConfig = new Config()
    serverConfig.groupConfig.name = groupName
    serverConfig.groupConfig.password = groupPassword

    serverConfig.networkConfig.port = port
    serverConfig.networkConfig.portAutoIncrement = false
    serverConfig.networkConfig.join.multicastConfig.enabled = false
    configureServer(serverConfig)

    h1 = Hazelcast.newHazelcastInstance(serverConfig)

    def clientConfig = new ClientConfig()
    clientConfig.groupConfig.name = groupName
    clientConfig.groupConfig.password = groupPassword
    clientConfig.networkConfig.addAddress("127.0.0.1:$port")
    client = HazelcastClient.newHazelcastClient(clientConfig)
  }

  @Override
  def cleanupSpec() {
    try {
      Hazelcast.shutdownAll()
    } catch (Exception e) {
      e.printStackTrace()
    }
  }

  void configureServer(Config config) {
  }

  def setup() {
    randomName = randomResourceName()
  }

  void hazelcastTrace(ListWriterAssert writer, String name) {
    writer.trace(1) {
      hazelcastSpan(it, name)
    }
  }

  def hazelcastSpan(TraceAssert trace, String name, boolean isParent = true) {
    def matcher = name =~ resourceNamePattern
    assert matcher.matches()

    trace.span {
      serviceName "hazelcast-sdk"
      resourceName name
      operationName "hazelcast.invoke"
      spanType DDSpanTypes.HTTP_CLIENT
      errored false
      if (isParent) {
        parent()
      } else {
        childOfPrevious()
      }
      tags {
        "$Tags.COMPONENT" "hazelcast-sdk"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "hazelcast.name" matcher.group("name")
        "hazelcast.operation" matcher.group("operation")
        "hazelcast.service" "hz:impl:${matcher.group("service")}Service"
        "hazelcast.instance" client.name
        defaultTags()
      }
    }
  }

  def randomResourceName(int length = 8) {
    RandomString.make(length)
  }
}
