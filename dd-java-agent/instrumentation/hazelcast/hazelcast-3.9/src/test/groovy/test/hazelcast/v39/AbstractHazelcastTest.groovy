package test.hazelcast.v39

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import datadog.trace.agent.test.AgentTestRunner
import spock.lang.Shared

abstract class AbstractHazelcastTest extends AgentTestRunner {

  @Shared HazelcastInstance h1, client

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.hazelcast.enabled", "true")
  }


  @Override
  def setupSpec() {
    def serverConfig = new Config()
    h1 = Hazelcast.newHazelcastInstance(serverConfig)

    def clientConfig = new ClientConfig()
    client = HazelcastClient.newHazelcastClient( clientConfig )
  }

  @Override
  def cleanupSpec() {
    Hazelcast.shutdownAll()
  }
}
