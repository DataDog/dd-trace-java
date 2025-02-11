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
    if (!config.isApiSecurityEnabled() || ctx == null) {
      return false;
    }

    String route = ctx.getRoute();
    if (route == null) {
      return false;
    }

    String method = ctx.getMethod();
    if (method == null) {
      return false;
    }

    int statusCode = ctx.getResponseStatus();
    if (statusCode == 0) {
      return false;
    }

    return apiAccessTracker.updateApiAccessIfExpired(route, method, statusCode);
  }
}
