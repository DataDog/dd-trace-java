package com.datadog.appsec.gateway;

/** Exposes package-private {@link AppSecRequestContext} members to tests in other packages. */
public final class AppSecRequestContextTestBridge {

  private AppSecRequestContextTestBridge() {}

  public static void setMethod(AppSecRequestContext ctx, String method) {
    ctx.setMethod(method);
  }
}
