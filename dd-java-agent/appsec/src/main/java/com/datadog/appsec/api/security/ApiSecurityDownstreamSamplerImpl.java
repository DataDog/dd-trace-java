package com.datadog.appsec.api.security;

import com.datadog.appsec.gateway.AppSecRequestContext;
import java.util.concurrent.atomic.AtomicLong;

public class ApiSecurityDownstreamSamplerImpl implements ApiSecurityDownstreamSampler {

  private static final long KNUTH_FACTOR = 1111111111111111111L;
  private static final double SAMPLING_MAX = Math.pow(2, 64) - 1;

  private final AtomicLong globalRequestCount = new AtomicLong(0);
  private final double threshold;

  public ApiSecurityDownstreamSamplerImpl(double rate) {
    threshold = samplingCutoff(rate < 0.0 ? 0 : (rate > 1.0 ? 1 : rate));
  }

  private static double samplingCutoff(double rate) {
    if (rate < 0.5) {
      return (long) (rate * SAMPLING_MAX) + Long.MIN_VALUE;
    }
    if (rate < 1.0) {
      return (long) ((rate * SAMPLING_MAX) + Long.MIN_VALUE);
    }
    return Long.MAX_VALUE;
  }

  /**
   * First sample the request to ensure we randomize the request and then check if the current
   * server request has budget to analyze the downstream request.
   */
  @Override
  public boolean sampleHttpClientRequest(final AppSecRequestContext ctx, final long requestId) {
    final long counter = updateRequestCount();
    if (counter * KNUTH_FACTOR + Long.MIN_VALUE > threshold) {
      return false;
    }
    return ctx.sampleHttpClientRequest(requestId);
  }

  @Override
  public boolean isSampled(final AppSecRequestContext ctx, final long requestId) {
    return ctx.isHttpClientRequestSampled(requestId);
  }

  private long updateRequestCount() {
    return globalRequestCount.updateAndGet(cur -> (cur == Long.MAX_VALUE) ? 0L : cur + 1L);
  }
}
