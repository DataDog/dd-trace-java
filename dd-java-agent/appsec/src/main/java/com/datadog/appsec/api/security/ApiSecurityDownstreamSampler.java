package com.datadog.appsec.api.security;

import com.datadog.appsec.gateway.AppSecRequestContext;

public interface ApiSecurityDownstreamSampler {

  boolean sampleHttpClientRequest(AppSecRequestContext ctx, long requestId);

  boolean isSampled(AppSecRequestContext ctx, long requestId);

  class NoOp implements ApiSecurityDownstreamSampler {

    @Override
    public boolean sampleHttpClientRequest(AppSecRequestContext ctx, long requestId) {
      return false;
    }

    @Override
    public boolean isSampled(AppSecRequestContext ctx, long requestId) {
      return false;
    }
  }

  static ApiSecurityDownstreamSampler build(double rate) {
    if (rate < 0.0D) {
      rate = 0.D;
    } else if (rate > 1.0D) {
      rate = 1.0D;
    }
    return new ApiSecurityDownstreamSamplerImpl(rate);
  }
}
