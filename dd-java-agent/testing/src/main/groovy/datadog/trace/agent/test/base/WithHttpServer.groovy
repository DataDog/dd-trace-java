package datadog.trace.agent.test.base

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import okhttp3.OkHttpClient
import spock.lang.Shared

import java.util.concurrent.TimeUnit

abstract class WithHttpServer<SERVER> extends AgentTestRunner {
  @Shared
  SERVER server = null
  @Shared
  OkHttpClient client = OkHttpUtils.client()
  @Shared
  ServerSocket socket = PortUtils.randomOpenSocket()
  @Shared
  int port = socket.localPort
  @Shared
  URI address = null

  def setupSpec() {
    // Set up other shared variables
    address = buildAddress()
    // Wait with closing the socket until right before we start the server
    socket.close()
    PortUtils.waitForPortToClose(port, 5, TimeUnit.SECONDS)
    server = startServer(port)
    PortUtils.waitForPortToOpen(port, 5, TimeUnit.SECONDS)
    println getClass().name + " http server started at: http://localhost:$port/"
  }

  def cleanupSpec() {
    if (server == null) {
      println getClass().name + " can't stop null server"
      return
    }
    stopServer(server)
    server = null
    PortUtils.waitForPortToClose(port, 10, TimeUnit.SECONDS)
    println getClass().name + " http server stopped at: http://localhost:$port/"
  }

  URI buildAddress() {
    return new URI("http://localhost:$port/")
  }

  abstract SERVER startServer(int port)

  abstract void stopServer(SERVER server)
}
