package com.datadog.appsec.gateway;

public class GatewayContext {
  public final boolean isTransient;
  public final boolean isRasp;

  public GatewayContext(final boolean isTransient, final boolean isRasp) {
    this.isTransient = isTransient;
    this.isRasp = isRasp;
  }
}
