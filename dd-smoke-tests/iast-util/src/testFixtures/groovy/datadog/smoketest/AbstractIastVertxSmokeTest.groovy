package datadog.smoketest


import groovy.transform.CompileDynamic
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import spock.lang.IgnoreIf

@CompileDynamic
abstract class AbstractIastVertxSmokeTest extends AbstractIastServerSmokeTest {

  void 'test header source'() {
    setup:
    final url = "http://localhost:${httpPort}/header"
    final request = new Request.Builder().url(url).header('header', 'headerValue').get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'headerValue' &&
        tainted.ranges[0].source.name == 'header' &&
        tainted.ranges[0].source.origin == 'http.request.header'
    }
  }

  void 'test header list source'() {
    setup:
    final url = "http://localhost:${httpPort}/headers"
    final request = new Request.Builder().url(url).header('header', 'headerValues').get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'headerValues' &&
        tainted.ranges[0].source.name == 'header' &&
        tainted.ranges[0].source.origin == 'http.request.header'
    }
  }

  void 'test parameter source'() {
    setup:
    final url = "http://localhost:${httpPort}/param?param=paramValue"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'paramValue' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }

  void 'test parameter list source'() {
    setup:
    final url = "http://localhost:${httpPort}/params?param=paramValues"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'paramValues' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }

  void 'test form source'() {
    setup:
    final url = "http://localhost:${httpPort}/form_attribute"
    final body = new FormBody.Builder().add('formAttribute', 'form').build()
    final request = new Request.Builder().url(url).post(body).build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'form' &&
        tainted.ranges[0].source.name == 'formAttribute' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }

  void 'test body string source'() {
    setup:
    def url = "http://localhost:${httpPort}/body/string"
    if (encoding != null) {
      url += "?encoding=${encoding}"
    }
    final body = RequestBody.create(MediaType.get('text/plain'), 'string_body')
    final request = new Request.Builder().url(url).post(body).build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'string_body' && tainted.ranges[0].source.origin == 'http.request.body'
    }

    where:
    encoding | _
    null     | _
    'utf-8'  | _
  }

  void 'test body json source'() {
    setup:
    final url = "http://localhost:${httpPort}/body/json"
    final body = RequestBody.create(MediaType.get('application/json'), '{ "my_key": "my_value" }')
    final request = new Request.Builder().url(url).post(body).build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'my_key' && tainted.ranges[0].source.origin == 'http.request.body'
    }
    hasTainted { tainted ->
      tainted.value == 'my_value' && tainted.ranges[0].source.origin == 'http.request.body'
    }
  }

  void 'test body json array source'() {
    setup:
    final url = "http://localhost:${httpPort}/body/jsonArray"
    final body = RequestBody.create(MediaType.get('application/json'), '[ "value_1", "value_2" ]')
    final request = new Request.Builder().url(url).post(body).build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'value_2' && tainted.ranges[0].source.origin == 'http.request.body'
    }
  }

  @IgnoreIf({ instance.ignoreCookies() })
  void 'test cookie'() {
    setup:
    final url = "http://localhost:${httpPort}/cookie"
    final request = new Request.Builder().url(url).header('Cookie', 'name=value').get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted {
      it.value == 'name' && it.ranges[0].source.origin == 'http.request.cookie.name'
    }
    hasTainted {
      it.value == 'value' &&
        it.ranges[0].source.name == 'name' &&
        it.ranges[0].source.origin == 'http.request.cookie.value'
    }
  }

  void 'test path param'() {
    setup:
    final url = "http://localhost:${httpPort}/path/value"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted {
      it.value == 'value' &&
        it.ranges[0].source.name == 'name' &&
        it.ranges[0].source.origin == 'http.request.path.parameter'
    }
  }

  void 'test event bus'() {
    setup:
    final url = "http://localhost:${httpPort}/eventBus"
    final body = RequestBody.create(MediaType.get('application/json'), '{ "name": "value" }')
    final request = new Request.Builder().url(url).post(body).build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'VALUE' && tainted.ranges[0].source.origin == 'http.request.body'
    }
  }

  void 'test unvalidated redirect reroute1'() {
    given:
    final url = "http://localhost:${httpPort}/unvaidatedredirectreroute1?path=rerouted"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'UNVALIDATED_REDIRECT' }
  }

  void 'test unvalidated redirect reroute2'() {
    given:
    final url = "http://localhost:${httpPort}/unvaidatedredirectreroute2?path=rerouted"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'UNVALIDATED_REDIRECT' }
  }

  void 'test unvalidated redirect location header'() {
    given:
    final url = "http://localhost:${httpPort}/unvaidatedredirectheader?name=Location&value=path"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul -> vul.type == 'UNVALIDATED_REDIRECT' }
  }

  protected boolean ignoreCookies() {
    return false
  }
}
