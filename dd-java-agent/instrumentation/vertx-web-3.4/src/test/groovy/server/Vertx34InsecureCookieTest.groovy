package server

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.InsecureCookieModule
import groovy.transform.CompileStatic
import okhttp3.Request

class Vertx34InsecureCookieTest extends IastVertx34Server {

  @CompileStatic
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('dd.iast.enabled', 'true')
  }


  void 'test insecure Cookie'(){
    given:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/vulnerability/setHeaderString?name=Set-Cookie&value=user-id%3D7"
    final request = new Request.Builder().url(url).build()

    when:
    final response = client.newCall(request).execute()
    final body = response.body().string()

    then:
    response.code() == 200
    body == 'success'

    1 * module.onCookie('user-id', '7', _, _, _)
  }
}
