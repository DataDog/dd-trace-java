package com.datadog.appsec.gateway;

import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.List;
import java.util.Map;

class NewRequestHeaderCallback implements TriConsumer<RequestContext, String, String> {
  @Override
  public void accept(RequestContext ctx_, String name, String value) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return;
    }

    if (name.equalsIgnoreCase("cookie")) {
      Map<String, List<String>> cookies = CookieCutter.parseCookieHeader(value);
      ctx.addCookies(cookies);
    } else {
      ctx.addRequestHeader(name, value);
    }
  }
}
