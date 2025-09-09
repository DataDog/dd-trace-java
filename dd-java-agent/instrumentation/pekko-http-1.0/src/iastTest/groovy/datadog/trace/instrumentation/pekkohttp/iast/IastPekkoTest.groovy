package datadog.trace.instrumentation.pekkohttp.iast

import com.datadog.iast.test.IastRequestTestRunner
import datadog.trace.api.iast.SourceTypes
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.Request.Builder
import okhttp3.RequestBody
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.nio.charset.StandardCharsets

import static org.hamcrest.Matchers.greaterThan

class IastPekkoTest extends IastRequestTestRunner {
  @Shared
  @AutoCleanup
  Closeable testWebServer

  void setupSpec() {
    // avoid pekko classes being load before instrumentation,
    // as we're adding methods/fields
    testWebServer = getClass().classLoader
      .loadClass("datadog.trace.instrumentation.pekkohttp.iast.PekkoIastTestWebServer")
      .newInstance([] as Object[])
    testWebServer.start()
  }

  protected String buildUrl(String path) {
    "http://localhost:${testWebServer.port}/$path"
  }

  void 'path variable'() {
    when:
    String url = buildUrl 'iast/path/myValue'
    def request = new Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: myValue (tainted)'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'myValue'
      range 0, 7, source(SourceTypes.REQUEST_PATH_PARAMETER, null, 'myValue')
    }
    toc.size() == 1
  }

  void 'cookie — #variant variant'() {
    when:
    String url = buildUrl "iast/$path"
    def request = new Builder()
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

    where:
    variant    | path
    'required' | 'cookie'
    'optional' | 'cookie_optional'
  }

  void 'all cookies  — variant #variant'(String variant) {
    when:
    String url = buildUrl "iast/all_cookies"
    def request = new Builder()
      .url(url)
      .addHeader('Cookie', 'var1=foo; var1=bar')
      .get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    // cookie names are not tainted
    response.body().string() == 'IAST: [[var1 (tainted), foo (tainted)], [var1 (tainted), bar (tainted)]]'

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
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_COOKIE_VALUE, 'var1', 'bar')
    }

    where:
    variant << ['all_cookies', 'all_cookies_scala']
  }

  void 'single header'() {
    when:
    String url = buildUrl 'iast/header'
    def request = new Builder()
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

  void 'all headers — variant #variant'(String variant) {
    when:
    String url = buildUrl "iast/$variant"
    def request = new Builder().url(url).get().build()
    def response = client.newCall(request).execute()
    String body = response.body().string()

    then:
    response.code() == 200
    body.matches(/.*User-Agent \(tainted\):okhttp\/[\d.]+ \(tainted\).*/)

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

    where:
    variant << ['all_headers', 'all_headers_req_ctx']
  }

  void 'query param'() {
    when:
    String url = buildUrl 'iast/query?var=bar'
    def request = new Builder().url(url).get().build()
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


  void 'query param — multiple values'() {
    when:
    String url = buildUrl 'iast/query_multival?var=foo&var=bar'
    def request = new Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: [bar (tainted), foo (tainted)]'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'foo'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var', 'foo')
    }
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var', 'bar')
    }
  }

  void 'all query params'() {
    when:
    String url = buildUrl 'iast/all_query?var1=foo&var1=bar&var2=a+b+c'
    def request = new Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: [var1 (tainted):[foo (tainted), bar (tainted)], var2 (tainted):[a b c (tainted)]]'

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

  void 'all query params — simple map variant'() {
    when:
    String url = buildUrl 'iast/all_query_simple_map?var1=foo&var1=bar&var2=a+b+c'
    def request = new Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: [var1 (tainted):bar (tainted), var2 (tainted):a b c (tainted)]'

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
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var1', 'bar')
    }
    toc.hasTaintedObject {
      value 'a b c'
      range 0, 5, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var2', 'a b c')
    }
  }

  void 'all query params — list variant'() {
    when:
    String url = buildUrl 'iast/all_query_list?var1=foo&var1=bar&var2=a+b+c'
    def request = new Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: [[var1 (tainted), foo (tainted)], [var1 (tainted), bar (tainted)], [var2 (tainted), a b c (tainted)]]'

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

  void 'query string via uri'() {
    when:
    String url = buildUrl 'iast/uri?var1=foo&var1=bar&var2=a+b+c'
    def request = new Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() ==
      """
      Path: /iast/uri
      Path Segments: [iast, uri]
      Query String: var1=foo&var1=bar&var2=a+b+c (tainted)
      Raw Query String var1=foo&var1=bar&var2=a+b+c (tainted)
      Query as MultiMap: [var1 (tainted):[foo (tainted), bar (tainted)], var2 (tainted):[a b c (tainted)]]
      """.stripIndent()

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'var1=foo&var1=bar&var2=a+b+c'
      range 0, 28, source(SourceTypes.REQUEST_QUERY, null, 'var1=foo&var1=bar&var2=a+b+c')
    }
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
    String url = buildUrl 'iast/form_single'
    FormBody requestBody = new FormBody.Builder()
      .add('var', 'bar')
      .build()
    def request = new Builder().url(url).post(requestBody).build()
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

  void 'form param — multipart variant'() {
    when:
    String url = buildUrl 'iast/form_single'
    RequestBody requestBody = new MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .addFormDataPart('var', 'bar')
      .build()
    def request = new Builder().url(url).post(requestBody).build()
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

  void 'form param — map variant'() {
    when:
    String url = buildUrl 'iast/form_map'
    FormBody requestBody = new FormBody.Builder()
      .add("var1", "foo")
      .add("var2", "bar")
      .build()
    def request = new Builder().url(url).post(requestBody).build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: [var1 (tainted):foo (tainted), var2 (tainted):bar (tainted)]'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'var1'
      range 0, 4, source(SourceTypes.REQUEST_PARAMETER_NAME, 'var1', 'var1')
    }
    toc.hasTaintedObject {
      value 'foo'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var1', 'foo')
    }
    toc.hasTaintedObject {
      value 'var2'
      range 0, 4, source(SourceTypes.REQUEST_PARAMETER_NAME, 'var2', 'var2')
    }
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var2', 'bar')
    }
  }

  void 'form param — #variant variant'() {
    when:
    String url = buildUrl "iast/form_${variant}"
    FormBody requestBody = new FormBody.Builder()
      .add("var", "foo")
      .add("var", "bar")
      .build()
    def request = new Builder().url(url).post(requestBody).build()
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == expectedBodyString

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'var'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_NAME, 'var', 'var')
    }
    toc.hasTaintedObject {
      value 'foo'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var', 'foo')
    }
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var', 'bar')
    }

    where:
    variant           | expectedBodyString
    'multi_map'       | 'IAST: [var (tainted):[bar (tainted), foo (tainted)]]'
    'list'            | 'IAST: [[var (tainted), foo (tainted)], [var (tainted), bar (tainted)]]'
    'urlencoded_only' | 'IAST: [var (tainted):[foo (tainted), bar (tainted)]]'
  }

  void 'json request — #variant variant'() {
    given:
    final json =  '''{
        "var1": "foo",
        "var2": ["foo2", "foo2"]
      }'''

    when:
    String url = buildUrl "iast/$variant"
    def request = new Builder().url(url).post(
      RequestBody.create(MediaType.get("application/json"), json.getBytes(StandardCharsets.US_ASCII))).build()
    def response = client.newCall(request).execute()
    def respBody = response.body().string()

    then:
    response.code() == 200
    respBody == 'IAST: [var1 (tainted):foo (tainted), var2 (tainted):[foo2 (tainted), foo2 (tainted)]]'

    when:
    def toc = finReqTaintedObjects

    then:
    // source values take the value of the full body as it's converted to string at TaintFutureHelper
    toc.hasTaintedObject {
      value 'var1'
      range 0, 4, source(SourceTypes.REQUEST_BODY, 'var1', json)
    }
    toc.hasTaintedObject {
      value 'var2'
      range 0, 4, source(SourceTypes.REQUEST_BODY, 'var2', json)
    }
    toc.hasTaintedObject {
      value 'foo'
      range 0, 3, source(SourceTypes.REQUEST_BODY, 'var1', json)
    }
    toc.hasTaintedObject {
      value 'foo2'
      range 0, 4, source(SourceTypes.REQUEST_BODY, 'var2', json)
    }

    where:
    variant << ['json', 'json_mufeu']
  }
}
