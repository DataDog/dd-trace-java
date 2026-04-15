package datadog.trace.instrumentation.sofarpc

import com.alipay.sofa.rpc.bootstrap.ProviderBootstrap
import com.alipay.sofa.rpc.config.ApplicationConfig
import com.alipay.sofa.rpc.config.ConsumerConfig
import com.alipay.sofa.rpc.config.ProviderConfig
import com.alipay.sofa.rpc.config.ServerConfig
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class SofaRpcTest extends InstrumentationSpecification {

  @Shared
  int port = 12201

  @Shared
  int errorPort = 12202

  @Shared
  ProviderBootstrap providerBootstrap

  @Shared
  ProviderBootstrap errorProviderBootstrap

  @Shared
  GreeterService greeterService

  @Shared
  FaultyService faultyService

  def setupSpec() {
    ApplicationConfig appConfig = new ApplicationConfig().setAppName("test-server")

    // Happy-path server: Bolt on port 12201
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

    greeterService =
      new ConsumerConfig<GreeterService>()
      .setApplication(new ApplicationConfig().setAppName("test-client"))
      .setInterfaceId(GreeterService.name)
      .setDirectUrl("bolt://127.0.0.1:${port}")
      .setProtocol("bolt")
      .setRegister(false)
      .setSubscribe(false)
      .refer()

    // Error-path server: Bolt on port 12202, separate interface to avoid registry conflict
    ServerConfig errorServerConfig =
      new ServerConfig()
      .setProtocol("bolt")
      .setHost("127.0.0.1")
      .setPort(errorPort)

    ProviderConfig<FaultyService> errorProviderConfig =
      new ProviderConfig<FaultyService>()
      .setApplication(appConfig)
      .setInterfaceId(FaultyService.name)
      .setRef(new FaultyServiceImpl())
      .setServer(errorServerConfig)
      .setRegister(false)

    errorProviderBootstrap = errorProviderConfig.export()

    faultyService =
      new ConsumerConfig<FaultyService>()
      .setApplication(new ApplicationConfig().setAppName("test-client"))
      .setInterfaceId(FaultyService.name)
      .setDirectUrl("bolt://127.0.0.1:${errorPort}")
      .setProtocol("bolt")
      .setRegister(false)
      .setSubscribe(false)
      .refer()
  }

  def cleanupSpec() {
    providerBootstrap?.unExport()
    errorProviderBootstrap?.unExport()
  }

  def "client and server spans created for synchronous Bolt RPC call"() {
    setup:
    String serviceUniqueName = GreeterService.name + ":1.0"

    when:
    // runUnderTrace gives the client trace 2 spans (caller + sofarpc.request), making it
    // unambiguously distinguishable from the 1-span server trace in assertTraces below.
    String reply = runUnderTrace("caller") { greeterService.sayHello("World") }

    then:
    reply == "Hello, World"

    and:
    assertTraces(2) {
      // trace(0): client side — 2 spans [caller, sofarpc.request(client)]
      trace(2) {
        basicSpan(it, "caller")
        span {
          operationName "sofarpc.request"
          resourceName "${serviceUniqueName}/sayHello"
          spanType "rpc"
          errored false
          childOf span(0)
          tags {
            "$Tags.RPC_SERVICE" serviceUniqueName
            "rpc.method" "sayHello"
            "rpc.system" "sofarpc"
            "sofarpc.protocol" "bolt"
            "component" "sofarpc-client"
            "span.kind" "client"
            peerServiceFrom(Tags.RPC_SERVICE)
            defaultTags()
          }
        }
      }
      // trace(1): server side — 1 span [sofarpc.request(server)], child of client span
      trace(1) {
        span {
          operationName "sofarpc.request"
          resourceName "${serviceUniqueName}/sayHello"
          spanType "rpc"
          errored false
          childOf trace(0).get(1)
          tags {
            "$Tags.RPC_SERVICE" serviceUniqueName
            "rpc.method" "sayHello"
            "rpc.system" "sofarpc"
            "sofarpc.protocol" "bolt"
            "component" "sofarpc-server"
            "span.kind" "server"
            defaultTags(true)
          }
        }
      }
    }
  }

  def "server error is marked on server span"() {
    setup:
    String serviceUniqueName = FaultyService.name + ":1.0"

    when:
    // SOFA RPC Bolt propagates server exceptions back to the client as a SofaRpcException.
    // The client-side AbstractCluster.invoke() returns the SofaResponse to the proxy layer,
    // which then throws — after our instrumentation's OnMethodExit has already closed the scope.
    // So the CLIENT span is not errored; only the SERVER span reflects the error.
    faultyService.fail()

    then:
    thrown(Exception)

    and:
    assertTraces(2) {
      // Traces sorted by root-span start time. Client span starts first (initiates the call),
      // so the client trace is trace(0) and the server trace is trace(1).
      trace(1) {
        span {
          operationName "sofarpc.request"
          resourceName "${serviceUniqueName}/fail"
          spanType "rpc"
          errored false
          tags {
            "$Tags.RPC_SERVICE" serviceUniqueName
            "rpc.method" "fail"
            "rpc.system" "sofarpc"
            "sofarpc.protocol" "bolt"
            "component" "sofarpc-client"
            "span.kind" "client"
            peerServiceFrom(Tags.RPC_SERVICE)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          operationName "sofarpc.request"
          resourceName "${serviceUniqueName}/fail"
          spanType "rpc"
          errored true
          childOf trace(0).get(0)
          tags {
            "$Tags.RPC_SERVICE" serviceUniqueName
            "rpc.method" "fail"
            "rpc.system" "sofarpc"
            "sofarpc.protocol" "bolt"
            "component" "sofarpc-server"
            "span.kind" "server"
            "error.message" { String }
            defaultTags(true)
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

  interface FaultyService {
    // Non-void return type: SOFA RPC Bolt throws SofaRpcException on client side
    // when the server returns an error response, which is what we verify in the test.
    String fail()
  }

  static class FaultyServiceImpl implements FaultyService {
    @Override
    String fail() {
      throw new IllegalStateException("something went wrong")
    }
  }
}
