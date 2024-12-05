package datadog.trace.instrumentation.jersey2.iast

import com.datadog.iast.test.IastRequestTestRunner
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.api.iast.SourceTypes
import okhttp3.FormBody
import okhttp3.Request
import spock.lang.Shared

import static org.hamcrest.Matchers.greaterThan

class IastJersey2JettyTest extends IastRequestTestRunner {

  @Shared
  HttpServer server

  void setupSpec() {
    server = getClass().classLoader
      .loadClass("datadog.trace.instrumentation.jersey2.JettyServer")
      .newInstance([] as Object[]) as HttpServer
    server.start()
  }

  void cleanupSpec() {
    server.stop()
  }

  protected String buildUrl(String path) {
    "${server.address()}$path"
  }

  void 'path variable'() {
    when:
    String url = buildUrl 'iast/path/myValue'
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: myValue (tainted)'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'myValue'
      range 0, 7, source(SourceTypes.REQUEST_PATH_PARAMETER, 'name', 'myValue')
    }
  }

  void 'all path variables'() {
    when:
    String url = buildUrl 'iast/all_path/myValue'
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: [[name:[myValue (tainted)]]]'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'myValue'
      range 0, 7, source(SourceTypes.REQUEST_PATH_PARAMETER, 'name', 'myValue')
    }
  }

  void 'query param'() {
    when:
    String url = buildUrl 'iast/query?var=bar'
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: bar (tainted)'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var', 'bar')
    }
  }

  void 'all query params'() {
    when:
    String url = buildUrl 'iast/all_query?var1=foo&var1=bar&var2=a+b+c'
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: [[var1 (tainted):[foo (tainted), bar (tainted)]], [var2 (tainted):[a b c (tainted)]]]'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'var1'
      range 0, 4, source(SourceTypes.REQUEST_PARAMETER_NAME, 'var1', 'var1')
    }
    toc.hasTaintedObject {
      value 'var2'
      range 0, 4, source(SourceTypes.REQUEST_PARAMETER_NAME, 'var2', 'var2')
    }
    toc.hasTaintedObject {
      value 'foo'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var1', 'foo')
    }
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var1', 'bar')
    }
    toc.hasTaintedObject {
      value 'a b c'
      range 0, 5, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var2', 'a b c')
    }
  }

  void 'form param'() {
    when:
    String url = buildUrl 'iast/form'
    def body = new FormBody.Builder()
      .add('var', 'bar')
      .build()
    def request = new Request.Builder().url(url).post(body).build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: bar (tainted)'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var', 'bar')
    }
  }

  void 'all form params'() {
    when:
    String url = buildUrl "iast/$variant"
    def body = new FormBody.Builder()
      .add('var1', 'foo')
      .add('var1', 'bar')
      .add('var2', 'a b c')
      .build()
    def request = new Request.Builder().url(url).post(body).build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: [[var1 (tainted):[foo (tainted), bar (tainted)]], [var2 (tainted):[a b c (tainted)]]]'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'var1'
      range 0, 4, source(SourceTypes.REQUEST_PARAMETER_NAME, 'var1', 'var1')
    }
    toc.hasTaintedObject {
      value 'var2'
      range 0, 4, source(SourceTypes.REQUEST_PARAMETER_NAME, 'var2', 'var2')
    }
    toc.hasTaintedObject {
      value 'foo'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var1', 'foo')
    }
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var1', 'bar')
    }
    toc.hasTaintedObject {
      value 'a b c'
      range 0, 5, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var2', 'a b c')
    }

    where:
    variant << ['all_form', 'all_form_map']
  }

  void 'cookie'() {
    when:
    String url = buildUrl "iast/cookie"
    def request = new Request.Builder()
      .url(url)
      .addHeader('Cookie', 'var1=bar')
      .get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: bar (tainted)'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_COOKIE_VALUE, 'var1', 'bar')
    }
  }

  void 'all cookies'() {
    when:
    String url = buildUrl "iast/all_cookies"
    def request = new Request.Builder()
      .url(url)
      .addHeader('Cookie', 'var1=foo')
      .get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    // cookie names are not tainted
    response.body().string() == 'IAST: [var1 (tainted):foo (tainted)]'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'var1'
      range 0, 4, source(SourceTypes.REQUEST_COOKIE_NAME, 'var1', 'var1')
    }
    toc.hasTaintedObject {
      value 'foo'
      range 0, 3, source(SourceTypes.REQUEST_COOKIE_VALUE, 'var1', 'foo')
    }
  }

  void 'header'() {
    when:
    String url = buildUrl 'iast/header'
    def request = new Request.Builder()
      .url(url)
      .addHeader('X-My-Header', 'bar')
      .get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: bar (tainted)'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_HEADER_VALUE, 'X-My-Header', 'bar')
    }
  }

  void 'all headers'() {
    when:
    String url = buildUrl "iast/all_headers"
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()
    String body = response.body().string()

    then:
    response.code() == 200
    body.matches(/.*User-Agent \(tainted\):\[okhttp\/[\d.]+ \(tainted\)].*/)

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'User-Agent'
      range 0, 10, source(SourceTypes.REQUEST_HEADER_NAME, 'User-Agent', 'User-Agent')
    }
    toc.hasTaintedObject {
      value ~/okhttp\/[\d.]+/
      range 0, greaterThan(7), source(SourceTypes.REQUEST_HEADER_VALUE, 'User-Agent', ~/okhttp\/[\d.]+/)
    }
  }
}
