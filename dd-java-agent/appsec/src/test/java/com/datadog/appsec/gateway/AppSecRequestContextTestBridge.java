package com.datadog.appsec.gateway;

import com.datadog.appsec.report.AppSecEvent;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Exposes package-private {@link AppSecRequestContext} members to tests in other packages. */
public final class AppSecRequestContextTestBridge {

  private AppSecRequestContextTestBridge() {}

  public static void setMethod(AppSecRequestContext ctx, String method) {
    ctx.setMethod(method);
  }

  public static Collection<AppSecEvent> transferCollectedEvents(AppSecRequestContext ctx) {
    return ctx.transferCollectedEvents();
  }

  public static Map<String, List<String>> getRequestHeaders(AppSecRequestContext ctx) {
    return ctx.getRequestHeaders();
  }

  public static Map<String, List<String>> getResponseHeaders(AppSecRequestContext ctx) {
    return ctx.getResponseHeaders();
  }
}
