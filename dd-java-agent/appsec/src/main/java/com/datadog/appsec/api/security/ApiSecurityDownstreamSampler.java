package com.datadog.appsec.api.security;

import com.datadog.appsec.gateway.AppSecRequestContext;

public interface ApiSecurityDownstreamSampler {

  boolean sampleHttpClientRequest(AppSecRequestContext ctx, long requestId);

  boolean isSampled(AppSecRequestContext ctx, long requestId);

  ApiSecurityDownstreamSampler INCLUDE_ALL =
      new ApiSecurityDownstreamSampler() {
        @Override
        public boolean sampleHttpClientRequest(AppSecRequestContext ctx, long requestId) {
          return true;
        }

        @Override
        public boolean isSampled(AppSecRequestContext ctx, long requestId) {
          return true;
        }
      };

  ApiSecurityDownstreamSampler INCLUDE_NONE =
      new ApiSecurityDownstreamSampler() {
        @Override
        public boolean sampleHttpClientRequest(AppSecRequestContext ctx, long requestId) {
          return false;
        }

        @Override
        public boolean isSampled(AppSecRequestContext ctx, long requestId) {
          return false;
        }
      };

  static ApiSecurityDownstreamSampler build(double rate) {
    return rate <= 0D
        ? INCLUDE_NONE
        : (rate >= 1D ? INCLUDE_ALL : new ApiSecurityDownstreamSamplerImpl(rate));
  }
}
