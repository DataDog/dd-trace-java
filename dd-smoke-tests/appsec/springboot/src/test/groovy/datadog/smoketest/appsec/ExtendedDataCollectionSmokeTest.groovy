package datadog.smoketest.appsec

import datadog.trace.agent.test.utils.OkHttpUtils
import okhttp3.Request

class ExtendedDataCollectionSmokeTest extends AbstractAppSecServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {

    String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(defaultAppSecProperties)
    command.add('-Ddd.appsec.collect.all.headers=true')
    command.add('-Ddd.appsec.header.collection.redaction.enabled=false')
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}"])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }


  void 'test all headers'(){
    given:
    def url = "http://localhost:${httpPort}/custom-headers"
    def client = OkHttpUtils.clientBuilder().build()
    def request = new Request.Builder()
      .url(url)
      .addHeader("User-Agent", "Arachni/v1")
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
    !rootSpan.metrics.containsKey('_dd.appsec.request.header_collection.discarded')
    rootSpan.meta.get('http.request.headers.x-my-header-1') == 'value1'
    rootSpan.meta.get('http.request.headers.x-my-header-2') == 'value2'
    rootSpan.meta.get('http.request.headers.x-my-header-3') == 'value3'
    rootSpan.meta.get('http.request.headers.x-my-header-4') == 'value4'
    rootSpan.meta.get('http.request.headers.content-type') == 'text/html'
    !rootSpan.metrics.containsKey('_dd.appsec.response.header_collection.discarded')
    rootSpan.meta.get('http.response.headers.x-test-header-1') == 'value1'
    rootSpan.meta.get('http.response.headers.x-test-header-2') == 'value2'
    rootSpan.meta.get('http.response.headers.x-test-header-3') == 'value3'
    rootSpan.meta.get('http.response.headers.x-test-header-4') == 'value4'
    rootSpan.meta.get('http.response.headers.x-test-header-5') == 'value5'
  }

  void 'No extended header collection if no appsec event'(){
    given:
    def url = "http://localhost:${httpPort}/custom-headers"
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
    !rootSpan.metrics.containsKey('_dd.appsec.request.header_collection.discarded')
    !rootSpan.metrics.containsKey('http.request.headers.x-my-header-1')
    !rootSpan.metrics.containsKey('http.request.headers.x-my-header-2')
    !rootSpan.metrics.containsKey('http.request.headers.x-my-header-3')
    !rootSpan.metrics.containsKey('http.request.headers.x-my-header-4')
    rootSpan.meta.get('http.request.headers.content-type') == 'text/html'
    !rootSpan.metrics.containsKey('_dd.appsec.response.header_collection.discarded')
    !rootSpan.metrics.containsKey('http.response.headers.x-test-header-1')
    !rootSpan.metrics.containsKey('http.response.headers.x-test-header-2')
    !rootSpan.metrics.containsKey('http.response.headers.x-test-header-3')
    !rootSpan.metrics.containsKey('http.response.headers.x-test-header-4')
    !rootSpan.metrics.containsKey('http.response.headers.x-test-header-5')
  }

  void 'test header budget exceeded with 50 headers'() {
    given:
    def url = "http://localhost:${httpPort}/exceedResponseHeaders"
    def client = OkHttpUtils.clientBuilder().build()
    // Build request with 50 custom headers
    def builder = new Request.Builder().url(url)
    builder.addHeader("User-Agent", "Arachni/v1")
    (1..50).each { i ->
      builder.addHeader("X-My-Header-${i}", "value${i}")
    }
    // Include content-type to trigger parsing
    builder.addHeader('Content-Type', 'text/html')
    def request = builder.get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 200

    when:
    waitForTraceCount(1)

    then:
    def rootSpan = this.rootSpans.toList()[0]
    // Check that the discarded metrics exists and are greater than 1
    rootSpan.metrics.containsKey('_dd.appsec.request.header_collection.discarded')
    rootSpan.metrics['_dd.appsec.request.header_collection.discarded'] > 1
    rootSpan.metrics.containsKey('_dd.appsec.response.header_collection.discarded')
    rootSpan.metrics['_dd.appsec.response.header_collection.discarded'] > 1
    // Ensure no more than 50 request headers collected
    def headerRequestKeys = rootSpan.meta.keySet().findAll { it.startsWith('http.request.headers.') }
    headerRequestKeys.size() <= 50
    def headerResponseKeys = rootSpan.meta.keySet().findAll { it.startsWith('http.response.headers.') }
    headerResponseKeys.size() <= 50
    // Ensure allowed headers are collected
    rootSpan.meta.get('http.request.headers.content-type') == 'text/html'
    rootSpan.meta.get('http.response.headers.content-language') == 'en-US'
  }



}
