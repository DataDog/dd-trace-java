package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import java.net.HttpCookie;
import java.util.LinkedList;
import java.util.List;
import javax.annotation.Nonnull;

public interface HttpResponseHeaderModule extends IastModule {

  interface ForHeader extends HttpResponseHeaderModule {
    void onHeader(@Nonnull String name, String value);
  }

  interface ForCookie extends HttpResponseHeaderModule {
    void onCookie(
        @Nonnull String name, String value, boolean isSecure, boolean isHttpOnly, String sameSite);
  }

  class Delegated implements ForHeader, ForCookie {

    private static final String SET_COOKIE_HEADER = "Set-Cookie";

    private final List<HttpResponseHeaderModule.ForHeader> forHeaders = new LinkedList<>();
    private final List<HttpResponseHeaderModule.ForCookie> forCookies = new LinkedList<>();

    public void clear() {
      forHeaders.clear();
      forCookies.clear();
    }

    public void addDelegate(@Nonnull final HttpResponseHeaderModule delegate) {
      if (delegate instanceof ForHeader) {
        forHeaders.add((ForHeader) delegate);
      }
      if (delegate instanceof ForCookie) {
        forCookies.add((ForCookie) delegate);
      }
    }

    @Override
    public void onHeader(@Nonnull final String name, final String value) {
      if (!forCookies.isEmpty() && SET_COOKIE_HEADER.equalsIgnoreCase(name)) {
        // TODO add support for same site
        final List<HttpCookie> cookies = HttpCookie.parse(value);
        if (!cookies.isEmpty()) {
          final HttpCookie first = cookies.get(0);
          onCookie(first.getName(), first.getValue(), first.getSecure(), first.isHttpOnly(), null);
        }
      }
      for (final ForHeader handler : forHeaders) {
        handler.onHeader(name, value);
      }
    }

    @Override
    public void onCookie(
        @Nonnull final String name,
        final String value,
        final boolean isSecure,
        final boolean isHttpOnly,
        final String sameSite) {
      for (final ForCookie handler : forCookies) {
        handler.onCookie(name, value, isSecure, isHttpOnly, sameSite);
      }
    }
  }
}
