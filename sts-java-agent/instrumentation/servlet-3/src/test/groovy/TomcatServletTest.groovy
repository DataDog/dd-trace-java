import com.google.common.io.Files
import stackstate.opentracing.STSTracer
import stackstate.trace.api.STSSpanTypes
import stackstate.trace.common.writer.ListWriter
import io.opentracing.util.GlobalTracer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.catalina.Context
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.JarScanFilter
import org.apache.tomcat.JarScanType
import spock.lang.Timeout
import spock.lang.Unroll
import stackstate.trace.agent.test.AgentTestRunner

import java.lang.reflect.Field

@Timeout(5)
class TomcatServletTest extends AgentTestRunner {

  static final int PORT = randomOpenPort()
  OkHttpClient client = new OkHttpClient.Builder()
  // Uncomment when debugging:
//    .connectTimeout(1, TimeUnit.HOURS)
//    .writeTimeout(1, TimeUnit.HOURS)
//    .readTimeout(1, TimeUnit.HOURS)
    .build()

  Tomcat tomcatServer
  Context appContext

  ListWriter writer = new ListWriter()
  STSTracer tracer = new STSTracer(writer)

  def setup() {
    tomcatServer = new Tomcat()
    tomcatServer.setPort(PORT)

    def baseDir = Files.createTempDir()
    baseDir.deleteOnExit()
    tomcatServer.setBaseDir(baseDir.getAbsolutePath())

    final File applicationDir = new File(baseDir, "/webapps/ROOT")
    if (!applicationDir.exists()) {
      applicationDir.mkdirs()
    }
    appContext = tomcatServer.addWebapp("", applicationDir.getAbsolutePath())
    // Speed up startup by disabling jar scanning:
    appContext.getJarScanner().setJarScanFilter(new JarScanFilter() {
      @Override
      boolean check(JarScanType jarScanType, String jarName) {
        return false
      }
    })

    Tomcat.addServlet(appContext, "syncServlet", new TestServlet.Sync())
    appContext.addServletMappingDecoded("/sync", "syncServlet")

    Tomcat.addServlet(appContext, "asyncServlet", new TestServlet.Async())
    appContext.addServletMappingDecoded("/async", "asyncServlet")

    tomcatServer.start()
    System.out.println(
      "Tomcat server: http://" + tomcatServer.getHost().getName() + ":" + PORT + "/")


    try {
      GlobalTracer.register(tracer)
    } catch (final Exception e) {
      // Force it anyway using reflection
      final Field field = GlobalTracer.getDeclaredField("tracer")
      field.setAccessible(true)
      field.set(null, tracer)
    }
    writer.start()
    assert GlobalTracer.isRegistered()
  }

  def cleanup() {
    tomcatServer.stop()
    tomcatServer.destroy()
  }

  @Unroll
  def "test #path servlet call"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$PORT/$path")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().string().trim() == expectedResponse
    writer.size() == 2 // second (parent) trace is the okhttp call above...
    def trace = writer.firstTrace()
    trace.size() == 1
    def span = trace[0]

    span.context().serviceName == "unnamed-java-app"
    span.context().operationName == "servlet.request"
    span.context().resourceName == "GET /$path"
    span.context().spanType == STSSpanTypes.WEB_SERVLET
    !span.context().getErrorFlag()
    span.context().parentId != 0 // parent should be the okhttp call.
    span.context().tags["http.url"] == "http://localhost:$PORT/$path"
    span.context().tags["http.method"] == "GET"
    span.context().tags["span.kind"] == "server"
    span.context().tags["component"] == "java-web-servlet"
    span.context().tags["http.status_code"] == 200
    span.context().tags["span.hostname"] != null
    span.context().tags["span.pid"] != 0l
    span.context().tags["thread.name"] != null
    span.context().tags["thread.id"] != null
    span.context().tags.size() == 10

    where:
    path    | expectedResponse
    "async" | "Hello Async"
    "sync"  | "Hello Sync"
  }

  @Unroll
  def "test #path error servlet call"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$PORT/$path?error=true")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().string().trim() != expectedResponse
    writer.size() == 2 // second (parent) trace is the okhttp call above...
    def trace = writer.firstTrace()
    trace.size() == 1
    def span = trace[0]

    span.context().serviceName == "unnamed-java-app"
    span.context().operationName == "servlet.request"
    span.context().resourceName == "GET /$path"
    span.context().spanType == STSSpanTypes.WEB_SERVLET
    span.context().getErrorFlag()
    span.context().parentId != 0 // parent should be the okhttp call.
    span.context().tags["http.url"] == "http://localhost:$PORT/$path"
    span.context().tags["http.method"] == "GET"
    span.context().tags["span.kind"] == "server"
    span.context().tags["component"] == "java-web-servlet"
    span.context().tags["http.status_code"] == 500
    span.context().tags["span.hostname"] != null
    span.context().tags["span.pid"] != 0l
    span.context().tags["thread.name"] != null
    span.context().tags["thread.id"] != null
    span.context().tags["error"] == true
    span.context().tags["error.msg"] == "some $path error"
    span.context().tags["error.type"] == RuntimeException.getName()
    span.context().tags["error.stack"] != null
    span.context().tags.size() == 14

    where:
    path   | expectedResponse
    //"async" | "Hello Async" // FIXME: I can't seem get the async error handler to trigger
    "sync" | "Hello Sync"
  }

  @Unroll
  def "test #path error servlet call for non-throwing error"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$PORT/$path?non-throwing-error=true")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().string().trim() != expectedResponse
    writer.size() == 2 // second (parent) trace is the okhttp call above...
    def trace = writer.firstTrace()
    trace.size() == 1
    def span = trace[0]

    span.context().serviceName == "unnamed-java-app"
    span.context().operationName == "servlet.request"
    span.context().resourceName == "GET /$path"
    span.context().spanType == STSSpanTypes.WEB_SERVLET
    span.context().getErrorFlag()
    span.context().parentId != 0 // parent should be the okhttp call.
    span.context().tags["http.url"] == "http://localhost:$PORT/$path"
    span.context().tags["http.method"] == "GET"
    span.context().tags["span.kind"] == "server"
    span.context().tags["component"] == "java-web-servlet"
    span.context().tags["http.status_code"] == 500
    span.context().tags["span.hostname"] != null
    span.context().tags["span.pid"] != 0l
    span.context().tags["thread.name"] != null
    span.context().tags["thread.id"] != null
    span.context().tags["error"] == true
    span.context().tags["error.msg"] == null
    span.context().tags["error.type"] == null
    span.context().tags["error.stack"] == null
    span.context().tags.size() == 11

    where:
    path   | expectedResponse
    "sync" | "Hello Sync"
  }

  private static int randomOpenPort() {
    new ServerSocket(0).withCloseable {
      it.setReuseAddress(true)
      return it.getLocalPort()
    }
  }
}
