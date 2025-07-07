package smoketest

import datadog.environment.JavaVirtualMachine
import datadog.smoketest.AbstractIastServerSmokeTest
import datadog.trace.api.config.IastConfig
import okhttp3.Request
import spock.lang.IgnoreIf

@IgnoreIf({
  System.getProperty("java.vendor").contains("IBM") && System.getProperty("java.version").contains("1.8.")
})
class ResteasySmokeTest extends AbstractIastServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String jarPath = System.getProperty("datadog.smoketest.resteasy.jar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(IastConfig.IAST_ENABLED, true),
      withSystemProperty(IastConfig.IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IastConfig.IAST_DEBUG_ENABLED, true)
    ])
    if (JavaVirtualMachine.isJavaVersionAtLeast(17)) {
      command.addAll(["--add-opens", "java.base/java.lang=ALL-UNNAMED"])
    }
    command.addAll(["-jar", jarPath, Integer.toString(httpPort)])
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
    hasTainted { tainted ->
      tainted.value == 'pathParamValue' &&
        tainted.ranges[0].source.origin == 'http.request.path.parameter'
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
    hasTainted { tainted ->
      tainted.value == 'queryParamValue' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
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
    hasTainted { tainted ->
      tainted.value == 'pepito' &&
        tainted.ranges[0].source.name == 'X-Custom-header' &&
        tainted.ranges[0].source.origin == 'http.request.header'
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
    hasTainted { tainted ->
      tainted.value == 'cookieValue' &&
        tainted.ranges[0].source.name == 'cookieName' &&
        tainted.ranges[0].source.origin == 'http.request.cookie.value'
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
    hasTainted { tainted ->
      tainted.value == 'value1' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
    hasTainted { tainted ->
      tainted.value == 'value2' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
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
    hasTainted { tainted ->
      tainted.value == 'setValue1' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
    hasTainted { tainted ->
      tainted.value == 'setValue2' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
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
    hasTainted { tainted ->
      tainted.value == 'sortedsetValue1' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
    hasTainted { tainted ->
      tainted.value == 'sortedsetValue2' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }

  void "unvalidated  redirect from location header is present"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/setlocationheader?param=setheader"

    when:
    def request = new Request.Builder().url(url).get().build()
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'UNVALIDATED_REDIRECT' }
  }

  void "unvalidated  redirect from location header is present"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/setresponselocation?param=setlocation"

    when:
    def request = new Request.Builder().url(url).get().build()
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'UNVALIDATED_REDIRECT' }
  }

  void "insecure cookie"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/insecurecookie"

    when:
    def request = new Request.Builder().url(url).get().build()
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'INSECURE_COOKIE' }
  }
}
