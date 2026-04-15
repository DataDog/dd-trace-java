package datadog.trace.instrumentation.sofarpc

import com.alipay.sofa.rpc.bootstrap.ProviderBootstrap
import com.alipay.sofa.rpc.config.ApplicationConfig
import com.alipay.sofa.rpc.config.ConsumerConfig
import com.alipay.sofa.rpc.config.ProviderConfig
import com.alipay.sofa.rpc.config.ServerConfig
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared

import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

/**
 * Tests SOFA RPC REST protocol instrumentation.
 *
 * REST works out-of-the-box via dd-trace-java's Apache HttpClient and HTTP server
 * instrumentation.  Our SOFA RPC instrumentation contributes the sofarpc.request[client]
 * span (from AbstractClusterInstrumentation).  There is no sofarpc.request[server] span
 * because no transport-level instrumentation sets SofaRpcProtocolContext for REST — the
 * server side is covered by JAX-RS / HTTP server instrumentation instead.
 */
class SofaRpcRestTest extends InstrumentationSpecification {

  @Shared
  int port = 12205

  @Shared
  ProviderBootstrap restProviderBootstrap

  @Shared
  GreeterService greeterService

  def setupSpec() {
    ServerConfig serverConfig =
      new ServerConfig()
        .setProtocol("rest")
        .setHost("127.0.0.1")
        .setPort(port)

    ProviderConfig<GreeterService> providerConfig =
      new ProviderConfig<GreeterService>()
        .setApplication(new ApplicationConfig().setAppName("test-server"))
        .setInterfaceId(GreeterService.name)
        .setRef(new GreeterServiceImpl())
        .setServer(serverConfig)
        .setRegister(false)

    restProviderBootstrap = providerConfig.export()

    greeterService =
      new ConsumerConfig<GreeterService>()
        .setApplication(new ApplicationConfig().setAppName("test-client"))
        .setInterfaceId(GreeterService.name)
        .setDirectUrl("rest://127.0.0.1:${port}")
        .setProtocol("rest")
        .setRegister(false)
        .setSubscribe(false)
        .refer()
  }

  def cleanupSpec() {
    restProviderBootstrap?.unExport()
  }

  def "client span created for REST call"() {
    setup:
    String serviceUniqueName = GreeterService.name + ":1.0"

    when:
    String reply = runUnderTrace("caller") { greeterService.sayHello("World") }

    then:
    reply == "Hello, World"

    and:
    // REST is instrumented by Apache HttpClient (client) + HTTP server (server) integrations.
    // Our SOFA RPC instrumentation adds sofarpc.request[client] on top.  No server-side
    // SofaRpcProtocolContext is set for REST, so ProviderProxyInvoker produces no sofarpc span.
    // Only the client-side trace is collected here.
    assertTraces(1) {
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
            "rpc.system" "sofarpc"
            "sofarpc.protocol" "rest"
            "component" "sofarpc-client"
            "span.kind" "client"
            peerServiceFrom(Tags.RPC_SERVICE)
            defaultTags()
          }
        }
      }
    }
  }

  @Path("/greeter")
  interface GreeterService {
    @GET
    @Path("/hello/{name}")
    @Produces(MediaType.TEXT_PLAIN)
    String sayHello(@PathParam("name") String name)
  }

  static class GreeterServiceImpl implements GreeterService {
    @Override
    String sayHello(String name) {
      return "Hello, ${name}"
    }
  }
}
