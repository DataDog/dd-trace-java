package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase

class HttpResponseHeaderModuleTest extends IastModuleImplTestBase{


  void 'report insecure cookie with public HttpResponseHeader.onCookie'() {
    given:
    final httpResponseHeaderModule = new HttpResponseHeaderModuleImpl()
    final insecureCookieModule = Mock(InsecureCookieModuleImpl)
    httpResponseHeaderModule.addDelegate(insecureCookieModule)

    when:
    httpResponseHeaderModule.onCookie("user-id", true, true, true)

    then:
    1 * insecureCookieModule.onCookie(_)
  }

  void 'report insecure cookie with HttpResponseHeader.onHeader'() {
    given:
    final httpResponseHeaderModule = new HttpResponseHeaderModuleImpl()
    final insecureCookieModule = Mock(InsecureCookieModuleImpl)
    httpResponseHeaderModule.addDelegate(insecureCookieModule)

    when:
    httpResponseHeaderModule.onHeader("Set-Cookie", "user-id=7")
    then:
    1 * insecureCookieModule.onCookie(_)
  }
}
