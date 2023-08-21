package server

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import groovy.transform.CompileDynamic
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.impl.CookieImpl
import io.vertx.ext.web.impl.RouterImpl
import io.vertx.ext.web.impl.RoutingContextImpl

import static java.util.Collections.emptySet

@CompileDynamic
class IastRoutingContextImplInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test that cookies are instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final router = Mock(RouterImpl)
    final request = Mock(HttpServerRequest) {
      path() >> '/'
    }
    final ctx = new RoutingContextImpl('/', router, request, emptySet())
    if (ctx.hasProperty('cookies')) {
      ctx.cookies = [name: new CookieImpl('name', 'value')]
    } else {
      request.getCookie('name') >> { new CookieImpl('name', 'value') }
    }

    when:
    final cookie = ctx.getCookie('name')

    then:
    1 * module.taint(SourceTypes.REQUEST_COOKIE_VALUE, _)

    when:
    cookie.getName()

    then:
    1 * module.taintIfInputIsTainted(SourceTypes.REQUEST_COOKIE_NAME, 'name', 'name', cookie)

    when:
    cookie.getValue()

    then:
    1 * module.taintIfInputIsTainted(SourceTypes.REQUEST_COOKIE_NAME, 'name', 'name', cookie)
    1 * module.taintIfInputIsTainted(SourceTypes.REQUEST_COOKIE_VALUE, 'name', 'value', cookie)
  }
}
