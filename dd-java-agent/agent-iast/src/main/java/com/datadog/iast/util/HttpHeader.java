package com.datadog.iast.util;

import com.datadog.iast.IastRequestContext;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class HttpHeader {

  final String name;

  HttpHeader(final String name) {
    this.name = name.toLowerCase(Locale.ROOT);
  }

  public boolean matches(final String name) {
    return this.name.equalsIgnoreCase(name);
  }

  @Nullable
  public static HttpHeader from(final String name) {
    return Values.HEADERS.get(name.toLowerCase(Locale.ROOT));
  }

  public abstract static class ContextAwareHeader extends HttpHeader {

    ContextAwareHeader(final String name) {
      super(name);
    }

    public abstract void onHeader(final IastRequestContext ctx, final String value);
  }

  public static final class Values {

    public static final HttpHeader X_FORWARDED_PROTO =
        new ContextAwareHeader("X-Forwarded-Proto") {

          @Override
          public void onHeader(final IastRequestContext ctx, final String value) {
            ctx.setxForwardedProto(value);
          }
        };
    public static final HttpHeader SET_COOKIE = new HttpHeader("Set-Cookie");
    public static final HttpHeader STRICT_TRANSPORT_SECURITY =
        new ContextAwareHeader("Strict-Transport-Security") {
          @Override
          public void onHeader(final IastRequestContext ctx, final String value) {
            ctx.setStrictTransportSecurity(value);
          }
        };
    public static final HttpHeader CONTENT_TYPE =
        new ContextAwareHeader("Content-Type") {
          @Override
          public void onHeader(final IastRequestContext ctx, final String value) {
            ctx.setContentType(value);
          }
        };
    public static final HttpHeader X_CONTENT_TYPE_OPTIONS =
        new ContextAwareHeader("X-Content-Type-Options") {
          @Override
          public void onHeader(final IastRequestContext ctx, final String value) {
            ctx.setxContentTypeOptions(value);
          }
        };

    public static final HttpHeader LOCATION = new HttpHeader("Location");
    public static final HttpHeader REFERER = new HttpHeader("Referer");
    public static final HttpHeader SEC_WEBSOCKET_LOCATION =
        new HttpHeader("Sec-WebSocket-Location");
    public static final HttpHeader SEC_WEBSOCKET_ACCEPT = new HttpHeader("Sec-WebSocket-Accept");
    public static final HttpHeader UPGRADE = new HttpHeader("Upgrade");
    public static final HttpHeader CONNECTION = new HttpHeader("Connection");
    public static final HttpHeader ACCESS_CONTROL_ALLOW_ORIGIN =
        new HttpHeader("Access-Control-Allow-Origin");

    /** Faster lookup for headers */
    static final Map<String, HttpHeader> HEADERS;

    static {
      HEADERS =
          Stream.of(
                  Values.X_FORWARDED_PROTO,
                  Values.SET_COOKIE,
                  Values.STRICT_TRANSPORT_SECURITY,
                  Values.CONTENT_TYPE,
                  Values.X_CONTENT_TYPE_OPTIONS,
                  Values.LOCATION,
                  Values.REFERER,
                  Values.SEC_WEBSOCKET_LOCATION,
                  Values.SEC_WEBSOCKET_ACCEPT,
                  Values.UPGRADE,
                  Values.CONNECTION,
                  Values.ACCESS_CONTROL_ALLOW_ORIGIN)
              .collect(Collectors.toMap(header -> header.name, Function.identity()));
    }
  }
}
