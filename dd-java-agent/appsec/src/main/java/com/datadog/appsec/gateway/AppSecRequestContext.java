package com.datadog.appsec.gateway;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;

/**
 * AppSec specific RequestContext implementation
 */
@SuppressWarnings("rawtypes")
public class AppSecRequestContext implements RequestContext {

  @Override
  public void addHeader(String key, String value) {
  }

  @Override
  public void addCookie(String key, String value) {
  }

  @Override
  public Flow finishHeaders() {
    return Flow.ResultFlow.empty();
  }

  @Override
  public Flow setRawURI(String uri) {
    return Flow.ResultFlow.empty();
  }
}
