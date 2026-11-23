package com.datadog.appsec.api.security;

import static datadog.trace.api.config.AppSecConfig.API_SECURITY_DOWNSTREAM_BODY_ANALYSIS_SAMPLE_RATE;
import static datadog.trace.api.config.AppSecConfig.API_SECURITY_DOWNSTREAM_REQUEST_ANALYSIS_SAMPLE_RATE;
import static datadog.trace.api.config.AppSecConfig.API_SECURITY_DOWNSTREAM_REQUEST_BODY_ANALYSIS_SAMPLE_RATE;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.Config;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiSecurityDownstreamSamplerTest extends DDJavaSpecification {

  @Test
  void testNoop() {
    // given
    AppSecRequestContext ctx = mock(AppSecRequestContext.class);
    ApiSecurityDownstreamSampler sampler = new ApiSecurityDownstreamSampler.NoOp();

    // when / then
    for (long requestId = 1; requestId <= 10; requestId++) {
      assertFalse(sampler.sampleHttpClientRequest(ctx, requestId));
    }

    // when / then
    for (long requestId = 1; requestId <= 10; requestId++) {
      assertFalse(sampler.isSampled(ctx, requestId));
    }
  }

  @ParameterizedTest
  @ValueSource(doubles = {-1.0, 0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0, 2.0})
  void testSamplingAlgorithm(double rate) {
    // given
    double epsilon = 0.05;
    double expectedRate = rate < 0 ? 0.0 : rate > 1 ? 1.0 : rate;
    AppSecRequestContext ctx = mock(AppSecRequestContext.class);
    when(ctx.sampleHttpClientRequest(anyLong())).thenReturn(true);
    when(ctx.isHttpClientRequestSampled(anyLong())).thenReturn(true);
    ApiSecurityDownstreamSampler sampler = new ApiSecurityDownstreamSamplerImpl(rate);
    int sampleCount = 100;

    // when
    int sampledCount = 0;
    for (long requestId = 1; requestId <= sampleCount; requestId++) {
      if (sampler.sampleHttpClientRequest(ctx, requestId)) {
        sampledCount++;
      }
    }

    // then
    double receivedRate = (double) sampledCount / sampleCount;
    assertEquals(expectedRate, receivedRate, epsilon);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        API_SECURITY_DOWNSTREAM_BODY_ANALYSIS_SAMPLE_RATE,
        API_SECURITY_DOWNSTREAM_REQUEST_ANALYSIS_SAMPLE_RATE,
        API_SECURITY_DOWNSTREAM_REQUEST_BODY_ANALYSIS_SAMPLE_RATE
      })
  void testConfigAliases(String env) {
    // setup
    injectSysConfig(env, "0.25");

    // when
    double value = Config.get().getApiSecurityDownstreamRequestBodyAnalysisSampleRate();

    // then
    assertEquals(0.25, value);
  }
}
