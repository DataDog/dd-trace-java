package server

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.taint.TaintedObjects
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
    final request = new Request.Builder().url(url).addHeader('Cookie', 'cookie=test').build()

    when:
    client.newCall(request).execute()

    then:
    (1.._) * module.taintObject(_ as TaintedObjects, _, SourceTypes.REQUEST_COOKIE_VALUE)
  }

  void 'test that getCookie is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/propagation/getcookie"
    final request = new Request.Builder().url(url).addHeader('Cookie', 'cookie=test').build()

    when:
    client.newCall(request).execute()

    then:
    (1.._) * module.taintObject(_ as TaintedObjects, _, SourceTypes.REQUEST_COOKIE_VALUE)
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
    1 * module.taintObjectIfTainted(_ as TaintedObjects, 'cookieName', _, SourceTypes.REQUEST_COOKIE_NAME, 'cookieName')
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
    1 * module.taintObjectIfTainted(_ as TaintedObjects, 'cookieName', _, SourceTypes.REQUEST_COOKIE_NAME, 'cookieName')
    1 * module.taintObjectIfTainted(_ as TaintedObjects, 'cookieValue', _, SourceTypes.REQUEST_COOKIE_VALUE, 'cookieName')
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
    1 * module.taintObject(_ as TaintedObjects, _, SourceTypes.REQUEST_HEADER_VALUE)
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
    1 * module.taintObject(_ as TaintedObjects, _, SourceTypes.REQUEST_PARAMETER_VALUE)
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
    1 * module.taintObject(_ as TaintedObjects, _ as MultiMap, SourceTypes.REQUEST_PARAMETER_VALUE) // once for formAttributes()
    1 * module.taintObject(_ as TaintedObjects, _ as MultiMap, SourceTypes.REQUEST_PARAMETER_VALUE) // once for params()
    1 * module.taintObjectIfTainted(_ as TaintedObjects, 'form', _ as MultiMap, SourceTypes.REQUEST_PARAMETER_VALUE, 'formAttribute')
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
    1 * module.taintObject(_ as TaintedObjects, _ as Buffer, SourceTypes.REQUEST_BODY)
    1 * module.taintObjectIfTainted(_ as TaintedObjects, '{ "my_key": "my_value" }', _ as Buffer)
  }


  @Override
  Class<AbstractVerticle> verticle() {
    IastSourceVerticle
  }
}
