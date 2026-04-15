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
 * Our instrumentation contributes sofarpc.request[client] (AbstractClusterInstrumentation)
 * and sofarpc.request[server] (RestServerHandlerInstrumentation + ProviderProxyInvokerInstrumentation).
 * Distributed trace propagation is delegated to dd-trace-java's HTTP instrumentation
 * (Apache HttpClient on the client side, Netty on the server side), which is not active in
 * this unit test — so the server span appears as a separate trace root here.
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

  def "client and server spans created for REST call"() {
    setup:
    String serviceUniqueName = GreeterService.name + ":1.0"

    when:
    String reply = runUnderTrace("caller") { greeterService.sayHello("World") }

    then:
    reply == "Hello, World"

    and:
    assertTraces(2) {
      // trace(0): client side — caller + sofarpc.request[client]
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
            "sofarpc.protocol" "rest"
            "component" "sofarpc-client"
            "span.kind" "client"
            peerServiceFrom(Tags.RPC_SERVICE)
            defaultTags()
          }
        }
      }
      // trace(1): server side — sofarpc.request[server].
      // SofaRequest.getTargetServiceUniqueName() is null on the server side for REST
      // (not propagated through the JAX-RS layer), so resourceName is the method name only
      // and rpc.service tag is absent. Parent link to the client trace is provided by
      // HTTP instrumentation (not active in this test), so this span is a trace root here.
      trace(1) {
        span {
          operationName "sofarpc.request"
          resourceName "sayHello"
          spanType "rpc"
          errored false
          tags {
            "rpc.method" "sayHello"
            "rpc.system" "sofarpc"
            "sofarpc.protocol" "rest"
            "component" "sofarpc-server"
            "span.kind" "server"
            defaultTags(true)
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
