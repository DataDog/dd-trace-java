package com.datadog.iast.util;

import com.datadog.iast.IastRequestContext;
import javax.annotation.Nullable;

public enum HttpHeader {
  X_FORWARDED_PROTO("X-Forwarded-Proto") {
    @Override
    public void addToContext(final IastRequestContext ctx, final String value) {
      ctx.setxForwardedProto(value);
    }
  },
  STRICT_TRANSPORT_SECURITY("Strict-Transport-Security") {
    @Override
    public void addToContext(final IastRequestContext ctx, final String value) {
      ctx.setStrictTransportSecurity(value);
    }
  },
  CONTENT_TYPE("Content-Type") {
    @Override
    public void addToContext(final IastRequestContext ctx, final String value) {
      ctx.setContentType(value);
    }
  },
  X_CONTENT_TYPE_OPTIONS("X-Content-Type-Options") {
    @Override
    public void addToContext(final IastRequestContext ctx, final String value) {
      ctx.setxContentTypeOptions(value);
    }
  },
  COOKIE("Cookie"),
  SET_COOKIE("Set-Cookie"),
  SET_COOKIE2("Set-Cookie2"),
  LOCATION("Location"),
  REFERER("Referer"),
  SEC_WEBSOCKET_LOCATION("Sec-WebSocket-Location"),
  SEC_WEBSOCKET_ACCEPT("Sec-WebSocket-Accept"),
  UPGRADE("Upgrade"),
  CONNECTION("Connection"),
  ORIGIN("Origin");

  /** Faster lookup for headers */
  private static final HttpHeaderMap<HttpHeader> HEADERS;

  static {
    HEADERS = new HttpHeaderMap<>();
    for (final HttpHeader header : values()) {
      HEADERS.put(header.name, header);
    }
  }

  public final String name;

  HttpHeader(final String name) {
    this.name = name;
  }

  public void addToContext(final IastRequestContext ctx, final String value) {}

  public boolean matches(@Nullable final String name) {
    return this.name.equalsIgnoreCase(name);
  }

  @Nullable
  public static HttpHeader from(final String name) {
    return HEADERS.get(name);
  }
}
