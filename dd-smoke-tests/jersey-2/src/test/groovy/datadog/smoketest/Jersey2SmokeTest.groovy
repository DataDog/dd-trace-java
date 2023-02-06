package datadog.smoketest

import datadog.trace.api.Platform
import okhttp3.Request

class Jersey2SmokeTest extends AbstractServerSmokeTest {

  @Override
  def logLevel(){
    return "debug"
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String jarPath = System.getProperty("datadog.smoketest.jersey2.jar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(datadog.trace.api.config.IastConfig.IAST_ENABLED, true),
      withSystemProperty(datadog.trace.api.config.IastConfig.IAST_REQUEST_SAMPLING, 100),
      withSystemProperty(datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED, true),
      withSystemProperty(datadog.trace.api.config.IastConfig.IAST_DEDUPLICATION_ENABLED, false),
      withSystemProperty("integration.grizzly.enabled", true)
    ])
    if (Platform.isJavaVersionAtLeast(17)){
      command.addAll((String[]) ["--add-opens", "java.base/java.lang=ALL-UNNAMED"])
    }
    command.addAll((String[]) ["-jar", jarPath, httpPort])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  def "Test path parameter"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/bypathparam/pathParamValue"
    boolean sqlInjectionFound = false

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()
    checkLog {
      if (it.contains("SQL_INJECTION") && it.contains("restserver.DB") && it.contains("pathParamValue")) {
        sqlInjectionFound = true
      }
    }

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello pathParamValue")
    assert response.code() == 200
    assert sqlInjectionFound
  }

  def "Test query parameter"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/byqueryparam?param=queryParamValue"
    boolean sqlInjectionFound = false

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()
    checkLog {
      if (it.contains("SQL_INJECTION") && it.contains("restserver.DB") && it.contains("queryParamValue")) {
        sqlInjectionFound = true
      }
    }

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello queryParamValue")
    assert response.code() == 200
    assert sqlInjectionFound
  }

  def "Test header"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/byheader"
    boolean sqlInjectionFound = false

    when:
    def request = new Request.Builder().url(url).header("X-Custom-header", "pepito").get().build()
    def response = client.newCall(request).execute()
    checkLog {
      if (it.contains("SQL_INJECTION") && it.contains("restserver.DB") && it.contains("pepito")) {
        sqlInjectionFound = true
      }
    }

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello pepito")
    assert response.code() == 200
    assert sqlInjectionFound
  }

  def "Test cookie"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/bycookie"
    boolean sqlInjectionFound = false

    when:
    def request = new Request.Builder().url(url).addHeader("Cookie", "cookieName=cookieValue").get().build()
    def response = client.newCall(request).execute()
    checkLog {
      if (it.contains("SQL_INJECTION") && it.contains("restserver.DB") && it.contains("cookieValue")) {
        sqlInjectionFound = true
      }
    }

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello cookieValue")
    assert response.code() == 200
    assert sqlInjectionFound
  }

  private static String withSystemProperty(final String config, final Object value) {
    return "-Ddd.${config}=${value}"
  }
}
