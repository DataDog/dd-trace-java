import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.Flaky
import rmi.app.Greeter
import rmi.app.Server
import rmi.app.ServerLegacy

import java.rmi.registry.LocateRegistry
import java.rmi.server.UnicastRemoteObject

import static datadog.trace.agent.test.asserts.ListWriterAssert.assertTraces
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

abstract class RmiTest extends VersionedNamingTestBase {
  def registryPort = PortUtils.randomOpenPort()
  def serverRegistry = LocateRegistry.createRegistry(registryPort)
  def clientRegistry = LocateRegistry.getRegistry("localhost", registryPort)


  @Override
  final String service() {
    return null
  }

  @Override
  final String operation() {
    return null
  }

  protected abstract String clientOperation()

  protected abstract String serverOperation()

  def cleanup() {
    UnicastRemoteObject.unexportObject(serverRegistry, true)
  }

  def "Client call creates spans"() {
    setup:
    def server = new Server()
    serverRegistry.rebind(Server.RMI_ID, server)

    when:
    def response = runUnderTrace("parent") {
      def client = (Greeter) clientRegistry.lookup(Server.RMI_ID)
      return client.hello("you")
    }

    then:
    response.contains("Hello you")
    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          resourceName "Greeter.hello"
          operationName clientOperation()
          childOf span(0)
          spanType DDSpanTypes.RPC
          measured true

          tags {
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.RPC_SERVICE" "rmi.app.Greeter"
            "$Tags.COMPONENT" "rmi-client"
            peerServiceFrom(Tags.RPC_SERVICE)
            defaultTags()
          }
        }
      }
      trace(2) {
        span {
          resourceName "Server.hello"
          operationName serverOperation()
          spanType DDSpanTypes.RPC
          measured true

          tags {
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.COMPONENT" "rmi-server"
            defaultTags(true)
          }
        }
        span {
          resourceName "Server.someMethod"
          operationName serverOperation()
          spanType DDSpanTypes.RPC
          measured true
          tags {
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.COMPONENT" "rmi-server"
            defaultTags()
          }
        }
      }
    }

    cleanup:
    serverRegistry.unbind("Server")
  }

  def "Calling server builtin methods doesn't create server spans"() {
    setup:
    def server = new Server()
    serverRegistry.rebind(Server.RMI_ID, server)

    when:
    server.equals(new Server())
    server.getRef()
    server.hashCode()
    server.toString()
    server.getClass()

    then:
    assertTraces(TEST_WRITER, 0) {}

    cleanup:
    serverRegistry.unbind("Server")
  }

  def "Service throws exception and its propagated to spans"() {
    setup:
    def server = new Server()
    serverRegistry.rebind(Server.RMI_ID, server)

    when:
    runUnderTrace("parent") {
      def client = (Greeter) clientRegistry.lookup(Server.RMI_ID)
      client.exceptional()
    }

    then:
    def thrownException = thrown(RuntimeException)
    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent", null, thrownException)
        span {
          resourceName "Greeter.exceptional"
          operationName clientOperation()
          childOf span(0)
          errored true
          spanType DDSpanTypes.RPC
          measured true

          tags {
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.RPC_SERVICE" "rmi.app.Greeter"
            "$Tags.COMPONENT" "rmi-client"
            errorTags(RuntimeException, String)
            peerServiceFrom(Tags.RPC_SERVICE)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          resourceName "Server.exceptional"
          operationName serverOperation()
          errored true
          spanType DDSpanTypes.RPC
          measured true

          tags {
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.COMPONENT" "rmi-server"
            errorTags(RuntimeException, String)
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    serverRegistry.unbind("Server")
  }

  @Flaky("Fails sometimes with NegativeArraySizeException in TimelinePrinter https://github.com/DataDog/dd-trace-java/issues/3869")
  def "Client call using ServerLegacy_stub creates spans"() {
    setup:
    def server = new ServerLegacy()
    serverRegistry.rebind(ServerLegacy.RMI_ID, server)

    when:
    def response = runUnderTrace("parent") {
      def client = (Greeter) clientRegistry.lookup(ServerLegacy.RMI_ID)
      return client.hello("you")
    }

    then:
    response.contains("Hello you")
    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          resourceName "Greeter.hello"
          operationName clientOperation()
          spanType DDSpanTypes.RPC
          childOf span(0)
          measured true
          tags {
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.COMPONENT" "rmi-client"
            "$Tags.RPC_SERVICE" "rmi.app.Greeter"
            peerServiceFrom(Tags.RPC_SERVICE)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          childOf trace(0)[1]
          resourceName "ServerLegacy.hello"
          operationName serverOperation()
          spanType DDSpanTypes.RPC
          measured true
          tags {
            "$Tags.COMPONENT" "rmi-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    serverRegistry.unbind(ServerLegacy.RMI_ID)
  }
}

class RmiV0Test extends RmiTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String serverOperation() {
    return "rmi.request"
  }

  @Override
  String clientOperation() {
    return "rmi.invoke"
  }
}

class RmiV1ForkedTest extends RmiTest {

  @Override
  int version() {
    return 1
  }

  @Override
  String serverOperation() {
    return "rmi.server.request"
  }

  @Override
  String clientOperation() {
    return "rmi.client.request"
  }
}
