package server


import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import groovy.transform.CompileDynamic
import io.vertx.core.AbstractVerticle
import io.vertx.core.MultiMap
import io.vertx.core.buffer.Buffer
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody

@CompileDynamic
class IastSourceTest extends IastVertx34Server {

  void 'test that cookies are instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/propagation/cookies"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.taintObjects(SourceTypes.REQUEST_COOKIE_VALUE, _)
  }

  void 'test that getCookie is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/propagation/getcookie"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.taintObject(SourceTypes.REQUEST_COOKIE_VALUE, _)
  }

  void 'test that cookie getName is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/propagation/getcookiename"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.taintIfInputIsTainted(SourceTypes.REQUEST_COOKIE_NAME, 'cookieName', 'cookieName', _)
  }

  void 'test that cookie getValue is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/propagation/getcookievalue"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.taintIfInputIsTainted(SourceTypes.REQUEST_COOKIE_NAME, 'cookieName', 'cookieName', _)
    1 * module.taintIfInputIsTainted(SourceTypes.REQUEST_COOKIE_VALUE, 'cookieName', 'cookieValue', _)
  }

  void 'test that headers() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/propagation/headers"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.taintObject(SourceTypes.REQUEST_HEADER_VALUE, _)
  }

  void 'test that params() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/propagation/params"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.taintObject(SourceTypes.REQUEST_PARAMETER_VALUE, _)
  }

  void 'test that formAttributes() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/propagation/formAttributes"
    final body = new FormBody.Builder().add('formAttribute', 'form').build()
    final request = new Request.Builder().url(url).post(body).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.taintObject(SourceTypes.REQUEST_PARAMETER_VALUE, _ as MultiMap) // once for formAttributes()
    1 * module.taintObject(SourceTypes.REQUEST_PARAMETER_VALUE, _ as MultiMap) // once for params()
    1 * module.taintIfInputIsTainted(SourceTypes.REQUEST_PARAMETER_VALUE, 'formAttribute', 'form', _ as MultiMap)
  }

  void 'test that handleData()/onData() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/propagation/handleData"
    final body = RequestBody.create(MediaType.get('application/json'), '{ "my_key": "my_value" }')
    final request = new Request.Builder().url(url).post(body).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.taintObject(SourceTypes.REQUEST_BODY, _ as Buffer)
    1 * module.taintIfInputIsTainted('{ "my_key": "my_value" }', _ as Buffer)
  }


  @Override
  Class<AbstractVerticle> verticle() {
    IastSourceVerticle
  }
}
