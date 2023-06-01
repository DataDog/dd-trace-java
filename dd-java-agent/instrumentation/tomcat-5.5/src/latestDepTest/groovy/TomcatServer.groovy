import com.google.common.io.Files
import datadog.trace.agent.test.base.HttpServer
import org.apache.catalina.Context
import org.apache.catalina.core.StandardHost
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.JarScanFilter
import org.apache.tomcat.JarScanType

class TomcatServer implements HttpServer {
  def port = 0
  final Tomcat server
  final String context
  final boolean dispatch

  TomcatServer(String context, boolean dispatch, Closure setupServlets) {
    this.context = context
    this.dispatch = dispatch
    server = new Tomcat()

    def baseDir = Files.createTempDir()
    baseDir.deleteOnExit()
    server.setBaseDir(baseDir.getAbsolutePath())

    server.setPort(0) // select random open port
    server.getConnector().enableLookups = true // get localhost instead of 127.0.0.1

    final File applicationDir = new File(baseDir, "/webapps/ROOT")
    if (!applicationDir.exists()) {
      applicationDir.mkdirs()
      applicationDir.deleteOnExit()
    }
    Context servletContext = server.addWebapp("/$context", applicationDir.getAbsolutePath())
    servletContext.allowCasualMultipartParsing = true
    // Speed up startup by disabling jar scanning:
    servletContext.getJarScanner().setJarScanFilter(new JarScanFilter() {
        @Override
        boolean check(JarScanType jarScanType, String jarName) {
          return false
        }
      })

    setupServlets(servletContext)

    (server.host as StandardHost).errorReportValveClass = TomcatServletTest.ErrorHandlerValve.name
  }

  @Override
  void start() {
    server.start()
    port = server.service.findConnectors()[0].localPort
    assert port > 0
  }

  @Override
  void stop() {
    server.stop()
    server.destroy()
  }

  @Override
  URI address() {
    if (dispatch) {
      return new URI("http://localhost:$port/$context/dispatch/")
    }
    return new URI("http://localhost:$port/$context/")
  }

  @Override
  String toString() {
    return this.class.name
  }
}
