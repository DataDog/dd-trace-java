package datadog.smoketest

import okhttp3.FormBody
import okhttp3.Request

class AbstractJerseySmokeTest extends AbstractIastServerSmokeTest {

  void 'path parameter'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/bypathparam/pathParamValue"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello pathParamValue")
    assert response.code() == 200
    hasTainted { tainted ->
      tainted.value == 'pathParamValue' &&
        tainted.ranges[0].source.origin == 'http.request.path.parameter'
    }
  }

  void 'query parameter'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/byqueryparam?param=queryParamValue"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello queryParamValue")
    assert response.code() == 200
    hasTainted { tainted ->
      tainted.value == 'queryParamValue' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }


  void 'header'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/byheader"

    when:
    def request = new Request.Builder().url(url).header("X-Custom-header", "pepito").get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello pepito")
    assert response.code() == 200
    hasTainted { tainted ->
      tainted.value == 'pepito' &&
        tainted.ranges[0].source.name == 'X-Custom-header' &&
        tainted.ranges[0].source.origin == 'http.request.header'
    }
  }

  void 'header name'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/headername"

    when:
    def request = new Request.Builder().url(url).header("X-Custom-header", "pepito").get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.equalsIgnoreCase("Jersey: hello X-Custom-header")
    assert response.code() == 200
    hasTainted { tainted ->
      tainted.value.equalsIgnoreCase('X-Custom-header') &&
        tainted.ranges[0].source.origin == 'http.request.header.name'
    }
  }

  void 'cookie'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/bycookie"

    when:
    def request = new Request.Builder().url(url).addHeader("Cookie", "cookieName=cookieValue").get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.equalsIgnoreCase("Jersey: hello cookieValue")
    assert response.code() == 200
    hasTainted { tainted ->
      tainted.value == 'cookieValue' &&
        tainted.ranges[0].source.name == 'cookieName' &&
        tainted.ranges[0].source.origin == 'http.request.cookie.value'
    }
  }

  void 'unvalidated  redirect from location header is present'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/setlocationheader?param=queryParamValue"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.isRedirect()
    hasVulnerability { vul -> vul.type == 'UNVALIDATED_REDIRECT' }
  }

  void 'unvalidated  redirect from location is present'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/setresponselocation?param=queryParamValue"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.isRedirect()
    hasVulnerability { vul -> vul.type == 'UNVALIDATED_REDIRECT' }
  }

  void 'cookie name from Cookie object'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/cookiename"

    when:
    def request = new Request.Builder().url(url).addHeader("Cookie", "cookieName=cookieValue").get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello cookieName")
    assert response.code() == 200
    hasTainted { tainted ->
      tainted.value == 'cookieName' &&
        tainted.ranges[0].source.origin == 'http.request.cookie.name'
    }
  }

  void 'cookie value from Cookie object'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/cookieobjectvalue"

    when:
    def request = new Request.Builder().url(url).addHeader("Cookie", "cookieName=cookieObjectValue").get().build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello cookieObjectValue")
    assert response.code() == 200
    hasTainted { tainted ->
      tainted.value == 'cookieObjectValue' &&
        tainted.ranges[0].source.name == 'cookieName' &&
        tainted.ranges[0].source.origin == 'http.request.cookie.value'
    }
  }

  void 'form parameter values'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/formparameter"

    when:
    def formBody = new FormBody.Builder()
    formBody.add("formParam1Name", "formParam1Value")
    def request = new Request.Builder().url(url).post(formBody.build()).build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello formParam1Value")
    assert response.code() == 200
    hasTainted { tainted ->
      tainted.value == 'formParam1Value' &&
        tainted.ranges[0].source.name == 'formParam1Name' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }

  void 'form parameter name'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/formparametername"

    when:
    def formBody = new FormBody.Builder()
    formBody.add("formParam1Name", "formParam1Value")
    def request = new Request.Builder().url(url).post(formBody.build()).build()
    def response = client.newCall(request).execute()

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello formParam1Name")
    assert response.code() == 200
    hasTainted { tainted ->
      tainted.value == 'formParam1Name' &&
        tainted.ranges[0].source.origin == 'http.request.parameter.name'
    }
  }

  void 'unvalidated  redirect from location header is present'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/setlocationheader?param=queryParamValue"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.isRedirect()
    hasVulnerability { vul -> vul.type == 'UNVALIDATED_REDIRECT' }
  }

  void 'unvalidated  redirect from location is present'() {
    setup:
    def url = "http://localhost:${httpPort}/hello/setresponselocation?param=queryParamValue"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.isRedirect()
    hasVulnerability { vul -> vul.type == 'UNVALIDATED_REDIRECT' }
  }
}
