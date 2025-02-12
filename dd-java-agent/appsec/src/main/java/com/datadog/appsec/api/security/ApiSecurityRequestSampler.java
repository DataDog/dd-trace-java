package com.datadog.appsec.api.security;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.Config;

public class ApiSecurityRequestSampler {

  private final ApiAccessTracker apiAccessTracker;
  private final Config config;

  public ApiSecurityRequestSampler(final Config config) {
    this.apiAccessTracker = new ApiAccessTracker();
    this.config = config;
  }

  public boolean sampleRequest(AppSecRequestContext ctx) {
    if (!isValid(ctx)) {
      return false;
    }

    return apiAccessTracker.updateApiAccessIfExpired(
        ctx.getRoute(), ctx.getMethod(), ctx.getResponseStatus());
  }

  public boolean preSampleRequest(AppSecRequestContext ctx) {
    if (!isValid(ctx)) {
      return false;
    }

    return apiAccessTracker.isApiAccessExpired(
        ctx.getRoute(), ctx.getMethod(), ctx.getResponseStatus());
  }

  private boolean isValid(AppSecRequestContext ctx) {
    return config.isApiSecurityEnabled()
        && ctx != null
        && ctx.getRoute() != null
        && ctx.getMethod() != null
        && ctx.getResponseStatus() != 0;
  }
}
