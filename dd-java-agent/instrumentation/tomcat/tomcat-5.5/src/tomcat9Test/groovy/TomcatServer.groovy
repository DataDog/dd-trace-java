import com.google.common.io.Files
import datadog.trace.agent.test.base.WebsocketServer
import org.apache.catalina.Context
import org.apache.catalina.core.StandardHost
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.JarScanFilter
import org.apache.tomcat.JarScanType
import org.apache.tomcat.websocket.server.WsSci

import javax.servlet.ServletContextEvent
import javax.servlet.ServletContextListener
import javax.websocket.server.ServerContainer
import java.nio.ByteBuffer

class TomcatServer implements WebsocketServer {

  def port = 0
  final Tomcat server
  final String context
  final boolean wsAsyncSend

  TomcatServer(String context, Closure setupServlets, Closure setupWebsockets, boolean wsAsyncSend = false) {
    this.context = context
    this.wsAsyncSend = wsAsyncSend
    server = new Tomcat()

    def baseDir = Files.createTempDir()
    baseDir.deleteOnExit()
    server.basedir = baseDir.absolutePath

    server.port = 0 // select random open port
    server.connector.enableLookups = true // get localhost instead of 127.0.0.1

    final File applicationDir = new File(baseDir, "/webapps/ROOT")
    if (!applicationDir.exists()) {
      applicationDir.mkdirs()
      applicationDir.deleteOnExit()
    }
    Context servletContext = server.addWebapp("/$context", applicationDir.getAbsolutePath())
    servletContext.allowCasualMultipartParsing = true
    // Speed up startup by disabling jar scanning:
    servletContext.jarScanner.jarScanFilter = new JarScanFilter() {
        @Override
        boolean check(JarScanType jarScanType, String jarName) {
          return false
        }
      }
    setupServlets(servletContext)
    servletContext.addServletContainerInitializer(new WsSci(), null)
    def listeners = new ArrayList(Arrays.asList(servletContext.getApplicationLifecycleListeners()))
    listeners.add(new EndpointDeployer(setupWebsockets))
    servletContext.setApplicationLifecycleListeners(EndpointDeployer.name)
    servletContext.setApplicationLifecycleListeners(listeners.toArray())

    (server.host as StandardHost).errorReportValveClass = TomcatWebsocketTest.ErrorHandlerValve.name
  }

  @Override
  void start() {
    server.start()
    port = server.service.findConnectors()[0].localPort
    assert port > 0
  }

  @Override
  void stop() {
    Thread.start {
      sleep 50
      // tomcat doesn't seem to interrupt accept() on stop()
      // so connect to force the loop to continue
      def sock = new Socket('localhost', port)
      sock.close()
    }
    server.stop()
    server.destroy()
  }

  @Override
  URI address() {
    return new URI("http://localhost:$port/$context/")
  }

  @Override
  String toString() {
    return this.class.name
  }

  @Override
  synchronized void awaitConnected() {
    synchronized (WsEndpoint) {
      try {
        while (WsEndpoint.activeSession == null) {
          WsEndpoint.wait(1000)
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt()
      }
    }
  }

  @Override
  void serverSendText(String[] messages) {
    if (wsAsyncSend && messages.length == 1) {
      // async does not support partial write
      WsEndpoint.activeSession.getAsyncRemote().sendText(messages[0])
    } else {
      if (messages.length == 1) {
        WsEndpoint.activeSession.getBasicRemote().sendText(messages[0])
      } else {
        def remoteEndpoint = WsEndpoint.activeSession.getBasicRemote()
        for (int i = 0; i < messages.length; i++) {
          remoteEndpoint.sendText(messages[i], i == messages.length - 1)
        }
      }
    }
  }

  @Override
  void serverSendBinary(byte[][] binaries) {
    if (wsAsyncSend && binaries.length == 1) {
      // async does not support partial write
      WsEndpoint.activeSession.getAsyncRemote().sendBinary(ByteBuffer.wrap(binaries[0]))
    } else {
      if (binaries.length == 1) {
        WsEndpoint.activeSession.getBasicRemote().sendBinary(ByteBuffer.wrap(binaries[0]))
      } else {
        try (def stream = WsEndpoint.activeSession.getBasicRemote().getSendStream()) {
          binaries.each {
            stream.write(it)
          }
        }
      }
    }
  }

  @Override
  void serverClose() {
    WsEndpoint.activeSession.close()
    WsEndpoint.activeSession = null
  }

  @Override
  void setMaxPayloadSize(int size) {
    WsEndpoint.activeSession.setMaxTextMessageBufferSize(size)
    WsEndpoint.activeSession.setMaxBinaryMessageBufferSize(size)
  }

  static class EndpointDeployer implements ServletContextListener {
    final Closure wsDeployCallback

    EndpointDeployer(Closure wsDeployCallback) {
      this.wsDeployCallback = wsDeployCallback
    }

    @Override
    void contextInitialized(ServletContextEvent sce) {
      wsDeployCallback.call((ServerContainer) sce.getServletContext().getAttribute(ServerContainer.name))
    }

    @Override
    void contextDestroyed(ServletContextEvent servletContextEvent) {
    }
  }
}
