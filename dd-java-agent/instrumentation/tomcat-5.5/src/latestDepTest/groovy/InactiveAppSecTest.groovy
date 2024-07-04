import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.instrumentation.servlet5.TestServlet5
import okhttp3.HttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.catalina.Context
import org.apache.catalina.Wrapper
import spock.lang.Shared
import spock.lang.Subject

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART

class InactiveAppSecTest extends AgentTestRunner {
  @Shared
  @Subject
  HttpServer server
  @Shared
  OkHttpClient client = OkHttpUtils.client(15, 15, TimeUnit.SECONDS)
  @Shared
  URI address

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.appsec.enabled", "")
  }

  def setupSpec() {
    server = new TomcatServer('tomcat-context', false, { Context ctx ->
      Wrapper wrapper = ctx.createWrapper()
      wrapper.name = UUID.randomUUID()
      wrapper.servletClass = TestServlet5.name
      wrapper.asyncSupported = true
      ctx.addChild(wrapper)
      ctx.addServletMappingDecoded(BODY_MULTIPART.path, wrapper.name)
    })
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

  void 'multipart requests still work'() {
    setup:
    def body = new MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .addFormDataPart('a', 'x')
      .build()
    def url = HttpUrl.get(address.resolve('/tomcat-context' + BODY_MULTIPART.path))
      .newBuilder().build()
    def request = new Request.Builder()
      .url(url)
      .method('POST', body).build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.body().charStream().text == '[a:[x]]'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).every {
      it.getTag('request.body.converted') == null
    }
  }
}
