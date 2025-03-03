package com.datadog.appsec.api.security;

import com.datadog.appsec.gateway.AppSecRequestContext;
import javax.annotation.Nonnull;

public interface ApiSecuritySampler {
  /**
   * Prepare a request context for later sampling decision. This method should be called at request
   * end, and is thread-safe. If a request can potentially be sampled, this method will return true.
   * If this method returns true, the caller MUST call {@link #releaseOne()} once the context is not
   * needed anymore.
   */
  boolean preSampleRequest(final @Nonnull AppSecRequestContext ctx);

  /** Get the final sampling decision. This method is NOT required to be thread-safe. */
  boolean sampleRequest(AppSecRequestContext ctx);

  /** Release one permit for the sampler. This must be called after processing a span. */
  void releaseOne();

  final class NoOp implements ApiSecuritySampler {
    @Override
    public boolean preSampleRequest(@Nonnull AppSecRequestContext ctx) {
      return false;
    }

    @Override
    public boolean sampleRequest(AppSecRequestContext ctx) {
      return false;
    }

    @Override
    public void releaseOne() {}
  }
}
