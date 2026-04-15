package datadog.trace.instrumentation.sofarpc

import com.alipay.sofa.rpc.bootstrap.ProviderBootstrap
import com.alipay.sofa.rpc.config.ApplicationConfig
import com.alipay.sofa.rpc.config.ConsumerConfig
import com.alipay.sofa.rpc.config.ProviderConfig
import com.alipay.sofa.rpc.config.ServerConfig
import datadog.trace.agent.test.InstrumentationSpecification
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

/**
 * Forked test: runs in an isolated JVM with gRPC instrumentation ENABLED (the default).
 *
 * When gRPC instrumentation is active, SOFA RPC Triple calls produce both gRPC spans
 * (grpc.client / grpc.server) and sofarpc spans.  All spans run in-process and share
 * one trace.  Expected hierarchy:
 *
 *   caller
 *     └─ sofarpc.request [client]
 *          └─ grpc.client
 *               └─ grpc.server
 *                    └─ sofarpc.request [server]   ← asserted below
 *
 * The key assertion is that sofarpc.request[server] is a direct child of grpc.server,
 * NOT of grpc.client.  Before the fix, TripleServerInstrumentation would extract the
 * parent context from gRPC Metadata regardless of whether a grpc.server span was active,
 * which caused sofarpc.request[server] to become a sibling of grpc.server instead.
 */
class SofaRpcTripleWithGrpcForkedTest extends InstrumentationSpecification {
  // No configurePreAgent() override — gRPC instrumentation is enabled by default.

  @Shared
  int triplePort = 12204

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

  def "Triple: server span is nested under grpc.server when gRPC instrumentation is enabled"() {
    when:
    String reply = runUnderTrace("caller") { greeterService.sayHello("World") }

    then:
    reply == "Hello, World"

    and:
    // Client spans (caller, sofarpc[client], grpc.client) are flushed when the client-side
    // root span finishes.  Server spans (grpc.server, sofarpc[server], grpc.message) are
    // flushed when grpc.server — the server-side local root — finishes on its own thread.
    // That gives two separate ListWriter entries even though both share the same trace_id.
    TEST_WRITER.waitForTraces(2)
    def allSpans = TEST_WRITER.flatten()

    def serverSofaSpan = allSpans.find {
      it.operationName.toString() == "sofarpc.request" && it.getTag("span.kind") == "server"
    }
    def grpcServerSpan = allSpans.find {
      it.operationName.toString() == "grpc.server"
    }

    assert serverSofaSpan != null : "Expected sofarpc[server]. Spans found: ${allSpans.collect { it.operationName.toString() + '[' + it.getTag('span.kind') + ']' }}"
    assert grpcServerSpan != null : "Expected grpc.server. Spans found: ${allSpans.collect { it.operationName.toString() + '[' + it.getTag('span.kind') + ']' }}"
    // sofarpc.request[server] must be a direct child of grpc.server, not grpc.client
    serverSofaSpan.parentId == grpcServerSpan.spanId
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
