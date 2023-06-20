package server

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.InsecureCookieModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import groovy.transform.CompileDynamic
import io.vertx.core.AbstractVerticle
import okhttp3.Request

@CompileDynamic
class IastSinksTest extends IastVertx34Server {

  void 'test unvalidated redirect reroute1'() {
    given:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/sinks/reroute1?path=rerouted"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.onRedirect("rerouted")
  }

  void 'test unvalidated redirect reroute2'() {
    given:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/sinks/reroute2?path=rerouted"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.onRedirect("rerouted")
  }

  void 'test unvalidated redirect location header'() {
    given:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/sinks/putheader1?name=Location&value=path"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.onHeader("Location", "path")
  }

  void 'test insecure Cookie'() {
    given:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/sinks/putheader1?name=Set-Cookie&value=user-id%3D7"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.onCookie('user-id', '7', _, _, _)
  }

  @Override
  Class<AbstractVerticle> verticle() {
    IastSinksVerticle
  }
}
