package smoketest

import datadog.smoketest.AbstractServerSmokeTest
import datadog.trace.api.Platform
import okhttp3.Request
import spock.lang.IgnoreIf

@IgnoreIf({
  System.getProperty("java.vendor").contains("IBM") && System.getProperty("java.version").contains("1.8.")
})
class ResteasySmokeTest extends AbstractServerSmokeTest {


  @Override
  def logLevel() {
    return "debug"
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String jarPath = System.getProperty("datadog.smoketest.resteasy.jar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(datadog.trace.api.config.IastConfig.IAST_ENABLED, true),
      withSystemProperty(datadog.trace.api.config.IastConfig.IAST_REQUEST_SAMPLING, 100),
      withSystemProperty(datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED, true),
      withSystemProperty(datadog.trace.api.config.IastConfig.IAST_DEDUPLICATION_ENABLED, false),
      withSystemProperty(datadog.trace.api.config.IastConfig.IAST_REDACTION_ENABLED, false)
    ])
    if (Platform.isJavaVersionAtLeast(17)) {
      command.addAll((String[]) ["--add-opens", "java.base/java.lang=ALL-UNNAMED"])
    }
    command.addAll((String[]) ["-jar", jarPath, httpPort])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  void "test path parameter"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/bypathparam/pathParamValue"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("RestEasy: hello pathParamValue")
    assert response.code() == 200
    processTestLogLines {
      it.contains("SQL_INJECTION") && it.contains("smoketest.resteasy.DB") && it.contains("pathParamValue")
    }
  }

  void "Test query parameter in RestEasy"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/byqueryparam?param=queryParamValue"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("RestEasy: hello queryParamValue")
    assert response.code() == 200
    processTestLogLines {
      it.contains("SQL_INJECTION") && it.contains("smoketest.resteasy.DB") && it.contains("queryParamValue")
    }
  }

  void "Test header in RestEasy"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/byheader"

    when:
    def request = new Request.Builder().url(url).header("X-Custom-header", "pepito").get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("RestEasy: hello pepito")
    assert response.code() == 200
    processTestLogLines {
      it.contains("SQL_INJECTION") && it.contains("smoketest.resteasy.DB") && it.contains("pepito")
    }
  }

  void "Test cookie in RestEasy"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/bycookie"

    when:
    def request = new Request.Builder().url(url).addHeader("Cookie", "cookieName=cookieValue").get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("RestEasy: hello cookieValue")
    assert response.code() == 200
    processTestLogLines {
      it.contains("SQL_INJECTION") && it.contains("smoketest.resteasy.DB") && it.contains("cookieValue")
    }
  }

  void "Test collection injection in RestEasy"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/collection?param=value1&param=value2"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("RestEasy: hello [value1, value2]")
    assert response.code() == 200
    processTestLogLines {
      it.contains("SQL_INJECTION") && it.contains("smoketest.resteasy.DB") && it.contains("value1")
    }
  }

  void "Test Set injection in RestEasy"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/set?param=setValue1&param=setValue2"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("setValue1")
    assert response.code() == 200
    processTestLogLines {
      it.contains("SQL_INJECTION") && it.contains("smoketest.resteasy.DB") && it.contains("setValue1")
    }
  }

  void "Test Sorted Set injection in RestEasy"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/sortedset?param=sortedsetValue1&param=sortedsetValue2"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("sortedsetValue1")
    assert response.code() == 200
    processTestLogLines {
      it.contains("SQL_INJECTION") && it.contains("smoketest.resteasy.DB") && it.contains("sortedsetValue1")
    }
  }

  void "unvalidated  redirect from location header is present"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/setlocationheader?param=setheader"

    when:
    def request = new Request.Builder().url(url).get().build()
    client.newCall(request).execute()

    then:
    processTestLogLines {
      it.contains("UNVALIDATED_REDIRECT") && it.contains("setheader")
    }
  }

  void "unvalidated  redirect from location header is present"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/setresponselocation?param=setlocation"

    when:
    def request = new Request.Builder().url(url).get().build()
    client.newCall(request).execute()

    then:
    processTestLogLines {
      it.contains("UNVALIDATED_REDIRECT") && it.contains("setlocation")
    }
  }


  private static String withSystemProperty(final String config, final Object value) {
    return "-Ddd.${config}=${value}"
  }
}
