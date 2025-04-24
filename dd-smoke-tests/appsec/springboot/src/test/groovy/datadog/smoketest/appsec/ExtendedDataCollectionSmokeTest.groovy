package datadog.smoketest.appsec

import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.ThreadUtils
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import spock.lang.Shared

import java.nio.charset.StandardCharsets

class ExtendedDataCollectionSmokeTest extends AbstractAppSecServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {

    String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(defaultAppSecProperties)
    command.add('-Ddd.appsec.collect.all.headers=true')
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}"])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }


  void 'test all headers'(){
    given:
    def url = "http://localhost:${httpPort}/greeting"
    def client = OkHttpUtils.clientBuilder().build()
    def request = new Request.Builder()
      .url(url)
      .addHeader('X-My-Header-1', "value1")
      .addHeader('X-My-Header-2', "value2")
      .addHeader('X-My-Header-3', "value3")
      .addHeader('X-My-Header-4', "value4")
      .addHeader('Content-Type', "text/html")
      .get()
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code()==200

    when:
    waitForTraceCount(1)

    then:
    def rootSpans = this.rootSpans.toList()
    rootSpans.size() == 1
    def rootSpan = rootSpans[0]
    rootSpan.meta.get('http.request.headers.x-my-header-1') == 'value1'
    rootSpan.meta.get('http.request.headers.x-my-header-2') == 'value2'
    rootSpan.meta.get('http.request.headers.x-my-header-3') == 'value3'
    rootSpan.meta.get('http.request.headers.x-my-header-4') == 'value4'
    rootSpan.meta.get('http.request.headers.content-type') == 'text/html'
    !rootSpan.meta.containsKey('_dd.appsec.request.header_collection.discarded')
  }
}
