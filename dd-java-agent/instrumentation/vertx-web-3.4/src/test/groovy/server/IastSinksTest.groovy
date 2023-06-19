package server

import com.datadog.iast.sink.InsecureCookieModuleImpl
import com.datadog.iast.sink.UnvalidatedRedirectModuleImpl
import datadog.trace.api.iast.InstrumentationBridge
import groovy.transform.CompileDynamic
import io.vertx.core.AbstractVerticle
import okhttp3.Request
import okhttp3.Response

@CompileDynamic
class IastSinksTest extends IastVertx34Server {

  void 'test unvalidated redirect reroute1'() {
    given:
    final module = Mock(UnvalidatedRedirectModuleImpl)
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
    final module = Mock(UnvalidatedRedirectModuleImpl)
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
    final module = Mock(UnvalidatedRedirectModuleImpl)
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
    final module = Mock(InsecureCookieModuleImpl)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/sinks/putheader1?name=Set-Cookie&value=user-id%3D7"
    final request = new Request.Builder().url(url).build()

    when:
    Response response = client.newCall(request).execute()

    then:
    response.isSuccessful()
    1 * module.onCookie(_)
  }

  @Override
  Class<AbstractVerticle> verticle() {
    IastSinksVerticle
  }
}
