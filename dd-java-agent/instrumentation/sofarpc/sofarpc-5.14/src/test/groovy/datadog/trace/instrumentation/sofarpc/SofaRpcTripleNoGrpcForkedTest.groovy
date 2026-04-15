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

/**
 * Forked test: runs in an isolated JVM with gRPC instrumentation disabled
 * (trace.grpc.enabled=false set before agent initialisation in configurePreAgent).
 *
 * PURPOSE: demonstrate the bug in ProviderProxyInvokerInstrumentation.
 *
 * Root cause: for Triple protocol the server-side span is started with
 *   startSpan(SOFA_RPC_SERVER)   // no explicit parentContext
 * relying on the gRPC TracingServerInterceptor having already activated a grpc.server
 * span on the current thread.  When gRPC instrumentation is absent, no such span
 * is active and the sofarpc.request[server] span becomes a disconnected root — the
 * distributed trace is broken.
 *
 * This test asserts the CORRECT, expected behaviour (server span connected to the
 * client span). It currently FAILS, proving the bug exists.
 *
 * Fix: in ProviderProxyInvokerInstrumentation, for Triple, extract propagation context
 * from the raw gRPC Metadata (accessible via TracingContextKey.getKeyMetadata() on the
 * gRPC Context) instead of depending on an active span from the gRPC instrumentation.
 */
class SofaRpcTripleNoGrpcForkedTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // Prevent GrpcServerBuilderInstrumentation from installing TracingServerInterceptor.
    // With this flag false the interceptor is never registered, so no grpc.server span
    // will be active on the thread when ProviderProxyInvoker.invoke() runs.
    injectSysConfig("trace.grpc.enabled", "false")
  }

  @Shared
  int triplePort = 12203

  @Shared
  ProviderBootstrap tripleProviderBootstrap

  @Shared
  GreeterService greeterService

  def setupSpec() {
    ServerConfig serverConfig =
      new ServerConfig()
      .setProtocol("tri")
      .setHost("127.0.0.1")
      .setPort(triplePort)

    ProviderConfig<GreeterService> providerConfig =
      new ProviderConfig<GreeterService>()
      .setApplication(new ApplicationConfig().setAppName("test-server"))
      .setInterfaceId(GreeterService.name)
      .setRef(new GreeterServiceImpl())
      .setServer(serverConfig)
      .setRegister(false)

    tripleProviderBootstrap = providerConfig.export()

    greeterService =
      new ConsumerConfig<GreeterService>()
      .setApplication(new ApplicationConfig().setAppName("test-client"))
      .setInterfaceId(GreeterService.name)
      .setDirectUrl("tri://127.0.0.1:${triplePort}")
      .setProtocol("tri")
      .setRegister(false)
      .setSubscribe(false)
      .refer()
  }

  def cleanupSpec() {
    tripleProviderBootstrap?.unExport()
  }

  /**
   * EXPECTED behaviour (currently fails — see class-level Javadoc).
   *
   * With gRPC disabled the only instrumentation active is the sofarpc one.
   * AbstractClusterInstrumentation injects the client span context into
   * SofaRequest.requestProps; SOFA RPC Triple serialises requestProps into
   * gRPC Metadata on the wire.  On the server side, however, SOFA RPC only
   * reconstructs the 8 framework-specific keys from gRPC Metadata back into
   * SofaRequest.requestProps — Datadog trace headers are NOT among them.
   * ProviderProxyInvokerInstrumentation therefore cannot extract the parent
   * context and falls back to creating a root span.
   *
   * Once fixed, this test should pass with assertTraces(2):
   *   trace(0): caller -> sofarpc.request[client]
   *   trace(1): sofarpc.request[server], childOf trace(0).get(1)
   */
  def "Triple: server span is linked to client trace when gRPC instrumentation is disabled"() {
    setup:
    // On the client side, SOFA RPC includes the default uniqueId (":1.0") in the service name.
    // On the server side (Triple/gRPC), the reconstructed SofaRequest does not carry the version,
    // so the service name is just the interface name.
    String clientServiceName = GreeterService.name + ":1.0"
    String serverServiceName = GreeterService.name

    when:
    String reply = runUnderTrace("caller") { greeterService.sayHello("World") }

    then:
    reply == "Hello, World"

    and:
    // BUG: actual result is assertTraces(2) where trace(1) has NO parent (root span),
    // so the childOf assertion below fails — the distributed trace is broken.
    assertTraces(2) {
      trace(2) {
        basicSpan(it, "caller")
        span {
          operationName "sofarpc.request"
          resourceName "${clientServiceName}/sayHello"
          spanType "rpc"
          errored false
          childOf span(0)
          tags {
            "$Tags.RPC_SERVICE" clientServiceName
            "rpc.system" "sofarpc"
            "sofarpc.protocol" "tri"
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
          resourceName "${serverServiceName}/sayHello"
          spanType "rpc"
          errored false
          // This childOf assertion currently fails: the span has no parent because
          // ProviderProxyInvokerInstrumentation called startSpan(SOFA_RPC_SERVER)
          // with no parentContext (gRPC span absent) and no requestProps extraction.
          childOf trace(0).get(1)
          tags {
            "$Tags.RPC_SERVICE" serverServiceName
            "rpc.system" "sofarpc"
            "sofarpc.protocol" "tri"
            "component" "sofarpc-server"
            "span.kind" "server"
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
}
