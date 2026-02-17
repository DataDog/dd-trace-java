import com.ibm.connector2.cics.ECIInteraction
import com.ibm.ctg.client.JavaGateway
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.CallDepthThreadLocalMap
import datadog.trace.bootstrap.instrumentation.api.Tags

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class JavaGatewayInterfaceInstrumentationTest extends InstrumentationSpecification {

  ServerSocket serverSocket
  Thread serverThread
  int port

  def setup() {
    // Start a server that accepts and immediately closes connections
    serverSocket = new ServerSocket(0)
    port = serverSocket.getLocalPort()
    serverThread = new Thread({
      try {
        while (!serverSocket.isClosed()) {
          Socket clientSocket = serverSocket.accept()
          clientSocket.close()
        }
      } catch (IOException ignored) {
        // expected when server socket closes
      }
    })
    serverThread.start()

    // Wait for server to be ready to accept connections
    // Try to connect to ensure the server thread has entered accept()
    int maxAttempts = 50
    int attemptDelayMs = 10
    for (int i = 0; i < maxAttempts; i++) {
      try {
        Socket testSocket = new Socket()
        testSocket.connect(new InetSocketAddress("127.0.0.1", port), 100)
        testSocket.close()
        break // Successfully connected, server is ready
      } catch (IOException e) {
        if (i == maxAttempts - 1) {
          throw new RuntimeException("Server failed to start accepting connections after ${maxAttempts * attemptDelayMs}ms", e)
        }
        Thread.sleep(attemptDelayMs)
      }
    }
  }

  def cleanup() {
    serverSocket?.close()
    serverThread?.join(1000)
  }

  def "flow without parent creates new span"() {
    when:
    try {
      new JavaGateway("127.0.0.1", port) // use IPv4 address so we can make sure peer.ipv4 is in the tags
    } catch (IOException ignored) {
      // expected - connection will be closed by server
    }

    then:
    assertTraces(1) {
      trace(1) {
        span(0) {
          operationName "gateway.flow"
          spanType DDSpanTypes.RPC
          errored true
          tags {
            "$Tags.COMPONENT" "cics-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "rpc.system" "cics"
            "$Tags.PEER_HOSTNAME" "127.0.0.1"
            "$Tags.PEER_PORT" port
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            errorTags IOException, String
            defaultTags()
          }
        }
      }
    }
  }

  def "flow with parent span merges into parent"() {
    when:
    try {
      runUnderTrace("parent") {
        // Simulate being inside ECIInteraction.execute() (cics.execute operation)
        CallDepthThreadLocalMap.incrementCallDepth(ECIInteraction)
        try {
          new JavaGateway("127.0.0.1", port) // use IPv4 address so we can make sure peer.ipv4 is in the tags
        } finally {
          CallDepthThreadLocalMap.decrementCallDepth(ECIInteraction)
        }
      }
    } catch (IOException ignored) {
      // expected - connection will be closed by server
    }

    then:
    assertTraces(1) {
      trace(1) {
        span(0) {
          operationName "parent"
          errored true
          tags {
            // Component and rpc.system are NOT set because we didn't create a new span
            // We only added connection tags to the existing parent span
            "$Tags.PEER_HOSTNAME" { it == null || it == "127.0.0.1" }
            "$Tags.PEER_PORT" port
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            errorTags IOException, String
            defaultTags()
          }
        }
      }
    }
  }
}
