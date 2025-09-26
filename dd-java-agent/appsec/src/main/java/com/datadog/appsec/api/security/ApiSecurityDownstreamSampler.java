package com.datadog.appsec.api.security;

import com.datadog.appsec.gateway.AppSecRequestContext;

public interface ApiSecurityDownstreamSampler {

  boolean sampleHttpClientRequest(AppSecRequestContext ctx, long requestId);

  boolean isSampled(AppSecRequestContext ctx, long requestId);

  class NoOp implements ApiSecurityDownstreamSampler {

    public static final NoOp INSTANCE = new NoOp();

    @Override
    public boolean sampleHttpClientRequest(AppSecRequestContext ctx, long requestId) {
      return false;
    }

    @Override
    public boolean isSampled(AppSecRequestContext ctx, long requestId) {
      return false;
    }
  }
}
