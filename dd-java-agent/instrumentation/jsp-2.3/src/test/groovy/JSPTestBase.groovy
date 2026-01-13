import com.google.common.io.Files
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import net.bytebuddy.utility.JavaModule
import okhttp3.OkHttpClient
import org.apache.catalina.Context
import org.apache.catalina.LifecycleException
import org.apache.catalina.startup.Tomcat
import spock.lang.Shared

abstract class JSPTestBase extends InstrumentationSpecification {
  @Shared
  int port
  @Shared
  Tomcat tomcatServer
  @Shared
  Context appContext
  @Shared
  String jspWebappContext = "jsptest-context"

  @Shared
  File baseDir
  @Shared
  String baseUrl

  OkHttpClient client = OkHttpUtils.client()

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    // skip jar scanning using environment variables:
    // http://tomcat.apache.org/tomcat-7.0-doc/config/systemprops.html#JAR_Scanning
    // having this set allows us to test with old versions of the tomcat api since
    // JarScanFilter did not exist in the tomcat 7 api
    System.setProperty("org.apache.catalina.startup.ContextConfig.jarsToSkip", "*")
    System.setProperty("org.apache.catalina.startup.TldConfig.jarsToSkip", "*")
  }

  def setupSpec() {
    baseDir = Files.createTempDir()
    baseDir.deleteOnExit()

    port = PortUtils.randomOpenPort()

    tomcatServer = new Tomcat()
    tomcatServer.setBaseDir(baseDir.getAbsolutePath())
    tomcatServer.setPort(port)
    tomcatServer.getConnector()
    // comment to debug
    tomcatServer.setSilent(true)
    // this is needed in tomcat 9, this triggers the creation of a connector, will not
    // affect tomcat 7 and 8
    // https://stackoverflow.com/questions/48998387/code-works-with-embedded-apache-tomcat-8-but-not-with-9-whats-changed
    tomcatServer.getConnector()

    baseUrl = "http://localhost:$port/$jspWebappContext"

    appContext = tomcatServer.addWebapp("/$jspWebappContext",
      getClass().getResource("/webapps/jsptest").getPath())

    tomcatServer.start()
    System.out.println(
      "Tomcat server: http://" + tomcatServer.getHost().getName() + ":" + port + "/")
  }

  def cleanupSpec() {
    try {
      tomcatServer.stop()
      tomcatServer.destroy()
    } catch (LifecycleException e) {
      // TODO Java 17: Failure during Catalina shutdown on JDK17
      if (new BigDecimal(System.getProperty("java.specification.version")).isAtLeast(17.0)) {
        e.printStackTrace(System.out)
      } else {
        throw e
      }
    }
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
}
