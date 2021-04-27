package test.hazelcast.v4

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
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import net.bytebuddy.utility.RandomString
import spock.lang.Shared

abstract class AbstractHazelcastTest extends AgentTestRunner {

  @Shared HazelcastInstance h1, client
  @Shared String randomName

  final resourceNamePattern = ~/^(?<operation>(?<service>[A-Z]\w+)\.[A-Z]\w+)(?: (?<name>.+))?$/

  /** Filter our Client operations. They can happen at seemingly random times and yield inconsistent test results. */
  final ListWriter.Filter defaultFilter = new ListWriter.Filter() {
    @Override
    boolean accept(List<DDSpan> trace) {
      return !(trace.size() == 1 && trace.get(0).getResourceName().startsWithAny("Client."))
    }
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.hazelcast.enabled", "true")
  }


  @Override
  def setupSpec() {
    def port = PortUtils.randomOpenPort()

    def serverConfig = new Config()

    serverConfig.networkConfig.port = port
    serverConfig.networkConfig.portAutoIncrement = false
    serverConfig.networkConfig.join.multicastConfig.enabled = false
    configureServer(serverConfig)

    h1 = Hazelcast.newHazelcastInstance(serverConfig)

    def clientConfig = new ClientConfig()
    clientConfig.networkConfig.addAddress("127.0.0.1:$port")
    client = HazelcastClient.newHazelcastClient(clientConfig)

    // Start using client and sleep to avoid initial join event
    def list = client.getList("null")
    list.add(1)
    list.remove(0)
    sleep(1000)
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
    TEST_WRITER.setFilter(defaultFilter)
    randomName = randomResourceName()
  }

  void hazelcastEventTrace(ListWriterAssert writer) {
    writer.trace(1) {
      span {
        serviceName "hazelcast-sdk"
        resourceName "Event.Handle"
        operationName "hazelcast.sdk"
        spanType DDSpanTypes.HTTP_CLIENT
        errored false
        parent()
        tags {
          "$Tags.COMPONENT" "hazelcast-sdk"
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
          "hazelcast.operation" "Event.Handle"
          "hazelcast.service" "Event"
          "hazelcast.correlationId" Long
          defaultTags()
        }
      }
    }
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
      operationName "hazelcast.sdk"
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
        "hazelcast.service" matcher.group("service")
        "hazelcast.instance" client.name
        "hazelcast.correlationId" Long
        defaultTags()
      }
    }
  }

  def randomResourceName(int length = 8) {
    RandomString.make(length)
  }
}
