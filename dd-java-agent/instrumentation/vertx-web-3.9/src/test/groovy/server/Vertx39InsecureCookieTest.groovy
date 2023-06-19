package server

import com.datadog.iast.sink.InsecureCookieModuleImpl
import datadog.trace.api.iast.InstrumentationBridge
import okhttp3.Request

class Vertx39InsecureCookieTest extends IastVertx39Server {


  void 'test insecure Cookie'(){
    given:
    final module = Mock(InsecureCookieModuleImpl)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/vulnerabilities/insecureCookie?name=user-id&value=7"
    final request = new Request.Builder().url(url).build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'Cookie Set'
    1 * module.onCookie(_)
  }

  void 'test secure Cookie'(){
    given:
    final module = Mock(InsecureCookieModuleImpl)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/vulnerabilities/insecureCookie?name=user-id&value=7&secure=true"
    final request = new Request.Builder().url(url).build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'Cookie Set'
    1 * module.onCookie(_)
  }
}
