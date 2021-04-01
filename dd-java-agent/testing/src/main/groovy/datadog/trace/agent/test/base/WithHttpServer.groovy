package datadog.trace.agent.test.base

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import net.bytebuddy.utility.JavaModule
import okhttp3.OkHttpClient
import spock.lang.Shared
import spock.lang.Subject

import java.util.concurrent.TimeUnit

abstract class WithHttpServer<SERVER> extends AgentTestRunner {
  @Subject
  @Shared
  SERVER server
  @Shared
  OkHttpClient client = OkHttpUtils.client()
  @Shared
  URI address

  def setupSpec() {
    int port = -1
    int retries = 3
    for (i in 0..<retries) {
      try {
        ServerSocket socket = PortUtils.randomOpenSocket()
        port = socket.localPort
        socket.close()
        PortUtils.waitForPortToClose(port, 5, TimeUnit.SECONDS)
        server = startServer(port)
        PortUtils.waitForPortToOpen(port, 5, TimeUnit.SECONDS)
        break
      } catch (Exception e) {
        e.printStackTrace()
        if (server != null) {
          println "Stopping existing server with port $port to retry"
          stopServer(server)
        }
      }
    }
    if (server == null) {
      throw new Exception("Failed to start server after $retries retries.")
    }
    address = buildAddress(port)
    println getClass().name + " http server started at: $address"
  }

  def cleanupSpec() {
    if (server == null) {
      println getClass().name + " can't stop null server"
      return
    }
    stopServer(server)
    server = null
    PortUtils.waitForPortToClose(address.port, 10, TimeUnit.SECONDS)
    println getClass().name + " http server stopped at: $address"
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

  abstract SERVER startServer(int port)

  abstract void stopServer(SERVER server)
}
