package com.datadog.appsec.gateway;

/** Exposes package-private {@link RateLimiter} members to tests in other packages. */
public final class RateLimiterTestBridge {

  private RateLimiterTestBridge() {}

  public static int getLimitPerSec(RateLimiter rateLimiter) {
    return rateLimiter.limitPerSec;
  }
}
