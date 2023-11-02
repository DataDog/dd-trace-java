package datadog.trace.agent.test.base

import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import net.bytebuddy.utility.JavaModule
import okhttp3.OkHttpClient
import spock.lang.Shared
import spock.lang.Subject

import java.lang.management.ManagementFactory
import java.lang.management.RuntimeMXBean
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

abstract class WithHttpServer<SERVER> extends VersionedNamingTestBase {

  @Shared
  @Subject
  HttpServer server

  @Lazy
  private static int timeoutValue = debugging ? 1500 : 15

  @Shared
  OkHttpClient client = OkHttpUtils.client(timeoutValue, timeoutValue, TimeUnit.SECONDS)

  @Shared
  URI address = null


  HttpServer server() {
    return new DefaultHttpServer()
  }

  private class DefaultHttpServer implements HttpServer {
    final ServerSocket socket = PortUtils.randomOpenSocket()
    final int port = socket.localPort
    SERVER server = null

    @Override
    void start() throws TimeoutException {
      // Wait with closing the socket until right before we start the server
      socket.close()
      PortUtils.waitForPortToClose(port, 5, TimeUnit.SECONDS)
      server = startServer(port)
      PortUtils.waitForPortToOpen(port, 5, TimeUnit.SECONDS)
    }

    @Override
    void stop() {
      stopServer(server)
    }

    @Override
    URI address() {
      return buildAddress(port)
    }

    @Override
    String toString() {
      return WithHttpServer.this.class.name + " Server"
    }
  }

  void setupSpec() {
    server = server()
    server.start()
    address = server.address()
    assert address.port > 0
    assert address.path.endsWith("/")
    println "$server started at: $address"
  }

  void cleanupSpec() {
    server.stop()
    println "$server stopped at: $address"
  }

  @Override
  void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
    if (throwable instanceof IllegalStateException
      && throwable.message.startsWith("Illegal access: this web application instance has been stopped already. Could not load")) {
      println "Ignoring class load error at shutdown"
    } else {
      super.onError(typeName, classLoader, module, loaded, throwable)
    }
  }


  URI buildAddress(int port) {
    return new URI("http://localhost:$port/")
  }

  SERVER startServer(int port) {
    throw new UnsupportedOperationException()
  }

  void stopServer(SERVER server) {
    throw new UnsupportedOperationException()
  }

  private static boolean isDebugging() {
    RuntimeMXBean runtimeMXBean = ManagementFactory.runtimeMXBean
    List<String> inputArguments = runtimeMXBean.inputArguments
    for (String arg : inputArguments) {
      if (arg.contains("-agentlib:jdwp")) {
        return true
      }
    }
    false
  }
}
