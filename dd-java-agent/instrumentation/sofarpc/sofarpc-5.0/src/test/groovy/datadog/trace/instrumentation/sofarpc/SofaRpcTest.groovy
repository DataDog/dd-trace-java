package datadog.trace.instrumentation.sofarpc

import com.alipay.sofa.rpc.bootstrap.ProviderBootstrap
import com.alipay.sofa.rpc.config.ApplicationConfig
import com.alipay.sofa.rpc.config.ConsumerConfig
import com.alipay.sofa.rpc.config.ProviderConfig
import com.alipay.sofa.rpc.config.ServerConfig
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared

class SofaRpcTest extends InstrumentationSpecification {

  @Shared
  int port = 12201

  @Shared
  ProviderBootstrap providerBootstrap

  @Shared
  GreeterService greeterService

  def setupSpec() {
    ApplicationConfig appConfig = new ApplicationConfig().setAppName("test-server")

    ServerConfig serverConfig =
      new ServerConfig()
      .setProtocol("bolt")
      .setHost("127.0.0.1")
      .setPort(port)

    ProviderConfig<GreeterService> providerConfig =
      new ProviderConfig<GreeterService>()
      .setApplication(appConfig)
      .setInterfaceId(GreeterService.name)
      .setRef(new GreeterServiceImpl())
      .setServer(serverConfig)
      .setRegister(false)

    providerBootstrap = providerConfig.export()

    ConsumerConfig<GreeterService> consumerConfig =
      new ConsumerConfig<GreeterService>()
      .setApplication(new ApplicationConfig().setAppName("test-client"))
      .setInterfaceId(GreeterService.name)
      .setDirectUrl("bolt://127.0.0.1:${port}")
      .setProtocol("bolt")
      .setRegister(false)
      .setSubscribe(false)

    greeterService = consumerConfig.refer()
  }

  def cleanupSpec() {
    providerBootstrap?.unExport()
  }

  def "client and server spans created for synchronous Bolt RPC call"() {
    setup:
    String serviceUniqueName = GreeterService.name + ":1.0"

    when:
    String reply = greeterService.sayHello("World")

    then:
    reply == "Hello, World"

    and:
    // Client and server are in the same JVM but use real TCP sockets (Bolt),
    // so each side produces its own local trace entry.
    assertTraces(2) {
      trace(1) {
        span {
          operationName "sofarpc.request"
          resourceName "${serviceUniqueName}/sayHello"
          spanType "rpc"
          errored false
          tags {
            "$Tags.RPC_SERVICE" serviceUniqueName
            "component" "sofarpc-client"
            "span.kind" "client"
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          operationName "sofarpc.request"
          resourceName "${serviceUniqueName}/sayHello"
          spanType "rpc"
          errored false
          tags {
            "$Tags.RPC_SERVICE" serviceUniqueName
            "component" "sofarpc-server"
            "span.kind" "server"
            defaultTags(true) // distributed root span — parent context propagated via Bolt headers
          }
        }
      }
    }
  }

  interface GreeterService {
    String sayHello(String name)
  }

  static class GreeterServiceImpl implements GreeterService {
    @Override
    String sayHello(String name) {
      return "Hello, ${name}"
    }
  }
}
