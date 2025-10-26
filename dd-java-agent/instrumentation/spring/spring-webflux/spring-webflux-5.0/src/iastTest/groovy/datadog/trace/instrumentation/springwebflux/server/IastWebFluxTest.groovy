package datadog.trace.instrumentation.springwebflux.server

import com.datadog.iast.test.IastRequestTestRunner
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.iast.SourceTypes
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.hamcrest.Matchers
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory
import org.springframework.boot.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.web.reactive.config.WebFluxConfigurer

import java.nio.charset.StandardCharsets

import static org.hamcrest.Matchers.equalToIgnoringCase

import spock.lang.Ignore

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application])
class IastWebFluxTest extends IastRequestTestRunner {

  @SpringBootApplication
  static class Application {
    @Bean
    NettyReactiveWebServerFactory nettyFactory() {
      new NettyReactiveWebServerFactory()
    }
  }

  @TestConfiguration
  class WebFluxConfig implements WebFluxConfigurer {

    @Override
    void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
      ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
      configurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(mapper))
      configurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(mapper))
    }
  }

  @LocalServerPort
  private int port

  OkHttpClient client = OkHttpUtils.client(true)


  protected String buildUrl(String path) {
    "http://localhost:$port/$path"
  }

  void 'path variable'() {
    when:
    String url = buildUrl 'iast/path/myValue'
    def request = new Request.Builder().url(url).get().build()
    def response =  client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: myValue (tainted)'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'myValue'
      range 0, 7, source(SourceTypes.REQUEST_PATH_PARAMETER, 'var1', 'myValue')
    }
  }

  void 'matrix variables'() {
    when:
    String url = buildUrl 'iast/matrix/foo;a=x,y'
    def request = new Request.Builder().url(url).get().build()
    def response =  client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: [a:[x, y]]'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'a'
      range 0, 1, source(SourceTypes.REQUEST_MATRIX_PARAMETER, 'var1', 'a')
    }
    toc.hasTaintedObject {
      value 'x'
      range 0, 1, source(SourceTypes.REQUEST_MATRIX_PARAMETER, 'var1', 'x')
    }
    toc.hasTaintedObject {
      value 'y'
      range 0, 1, source(SourceTypes.REQUEST_MATRIX_PARAMETER, 'var1', 'y')
    }
  }

  void 'query param'() {
    when:
    String url = buildUrl 'iast/query?var1=bar'
    def request = new Request.Builder().url(url).get().build()
    def response =  client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: bar (tainted)'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'var1'
      range 0, 4, source(SourceTypes.REQUEST_PARAMETER_NAME, 'var1', 'var1')
    }
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_PARAMETER_VALUE, 'var1', 'bar')
    }
  }

  void 'single header'() {
    when:
    String url = buildUrl 'iast/header'
    def request = new Request.Builder()
      .url(url)
      .addHeader('X-My-Header', 'bar')
      .get().build()
    def response =  client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'IAST: bar (tainted)'

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_HEADER_VALUE, equalToIgnoringCase('X-My-Header'), 'bar')
    }
  }

  void 'all headers'() {
    when:
    String url = buildUrl 'iast/all_headers'
    def request = new Request.Builder().url(url).get().build()
    def response =  client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string().contains('User-Agent (tainted):okhttp/')

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'User-Agent'
      range 0, 10, source(SourceTypes.REQUEST_HEADER_NAME, 'User-Agent', 'User-Agent')
    }
    toc.hasTaintedObject {
      value ~/okhttp\/[\d.]+/
      range 0, Matchers.greaterThan(7), source(SourceTypes.REQUEST_HEADER_VALUE, 'User-Agent', ~/okhttp\/[\d.]+/)
    }
  }

  void 'all headers — MultiValueMap variant'() {
    when:
    String url = buildUrl 'iast/all_headers_mvm'
    def request = new Request.Builder()
      .url(url)
      .addHeader('X-My-Header', 'foo1')
      .addHeader('X-My-Header', 'foo2')
      .get().build()
    def response = client.newCall(request).execute()
    def respString = response.body().string()

    then:
    response.code() == 200
    respString.contains('foo1 (tainted)')
    respString.contains('foo2 (tainted)')

    when:
    def toc = finReqTaintedObjects

    then:
    toc.hasTaintedObject {
      value 'X-My-Header'
      range 0, 11, source(SourceTypes.REQUEST_HEADER_NAME, 'X-My-Header', 'X-My-Header')
    }
    toc.hasTaintedObject {
      value 'foo1'
      range 0, 4, source(SourceTypes.REQUEST_HEADER_VALUE, 'X-My-Header', 'foo1')
    }
    toc.hasTaintedObject {
      value 'foo2'
      range 0, 4, source(SourceTypes.REQUEST_HEADER_VALUE, 'X-My-Header', 'foo2')
    }
  }

  void 'ServerHttpRequest methods'() {
    when:
    String url = buildUrl 'iast/shr?var1=foo'
    def request = new Request.Builder()
      .url(url)
      .header('cookie', 'var2=bar')
      .get().build()
    def response =  client.newCall(request).execute()
    def respBody = response.body().string()

    then:
    response.code() == 200
    (respBody =~ "URI: http://[^/]+/iast/shr\\?var1=foo").find()
    (respBody =~ "Path: /iast/shr").find()
    respBody.contains 'Query params: [var1 (tainted):[foo (tainted)]]'
    respBody.contains 'Cookies: [var2 (tainted):[var2 (tainted) -> bar (tainted)]]'
    (respBody =~ "User-Agent: okhttp/[\\d.]+ \\(tainted\\)").find()

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
      range 0, 4, source(SourceTypes.REQUEST_COOKIE_NAME, 'var2', 'var2')
    }
    toc.hasTaintedObject {
      value 'bar'
      range 0, 3, source(SourceTypes.REQUEST_COOKIE_VALUE, 'var2',  'bar')
    }
    toc.hasTaintedObject {
      value ~/okhttp\/[\d.]+/
      range 0, Matchers.greaterThan(7), source(SourceTypes.REQUEST_HEADER_VALUE, 'user-agent', ~/okhttp\/[\d.]+/)
    }
  }

  @Ignore("Not working under Groovy 4")
  void 'json request'() {
    when:
    String url = buildUrl 'iast/json'
    def request = new Request.Builder().url(url).post(
      RequestBody.create(MediaType.get("application/json"), '''{
        "var1": "foo",
        "var2": ["foo2", "foo2"]
      }'''.getBytes(StandardCharsets.US_ASCII))
      ).build()
    def response =  client.newCall(request).execute()
    def respBody = response.body().string()

    then:
    response.code() == 200
    respBody == 'IAST: MyJsonObject{var1=\'foo (tainted)\', var2=[foo2 (tainted), foo2 (tainted)]}'

    when:
    def toc = finReqTaintedObjects

    then:
    // source values take the value of the current object as the body is never converted to a CharSequence
    toc.hasTaintedObject {
      value 'foo'
      range 0, 3, source(SourceTypes.REQUEST_BODY, 'var1',  'foo')
    }
    toc.hasTaintedObject {
      value 'foo2'
      range 0, 4, source(SourceTypes.REQUEST_BODY, 'var2',  'foo2')
    }
  }
}
