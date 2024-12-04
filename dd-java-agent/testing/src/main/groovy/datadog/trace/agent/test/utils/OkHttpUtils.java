package datadog.trace.agent.test.utils;

import datadog.trace.agent.test.server.http.TestHttpServer;
import java.net.ProxySelector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.logging.HttpLoggingInterceptor.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class was moved from groovy to java because groovy kept trying to introspect on the
 * OkHttpClient class which contains java 8 only classes, which caused the build to fail for java 7.
 */
public class OkHttpUtils {

  private static final Logger CLIENT_LOGGER = LoggerFactory.getLogger("http-client");

  static {
    try {
      ((ch.qos.logback.classic.Logger) CLIENT_LOGGER).setLevel(ch.qos.logback.classic.Level.DEBUG);
    } catch (Throwable t) {
      CLIENT_LOGGER.warn("Unable to set debug level to client logger", t);
    }
  }

  private static final HttpLoggingInterceptor LOGGING_INTERCEPTOR =
      new HttpLoggingInterceptor(CLIENT_LOGGER::debug);

  private static final Interceptor EXPECT_CONTINUE_INTERCEPTOR =
      chain -> {
        final Request.Builder builder = chain.request().newBuilder();
        if (chain.request().body() != null) {
          builder.addHeader("Expect", "100-continue");
        }
        return chain.proceed(builder.build());
      };

  static {
    LOGGING_INTERCEPTOR.setLevel(Level.BASIC);
  }

  public static OkHttpClient.Builder clientBuilder() {
    final TimeUnit unit = TimeUnit.MINUTES;
    return new OkHttpClient.Builder()
        .addInterceptor(EXPECT_CONTINUE_INTERCEPTOR)
        .addInterceptor(LOGGING_INTERCEPTOR)
        .connectTimeout(1, unit)
        .writeTimeout(1, unit)
        .readTimeout(1, unit);
  }

  public static OkHttpClient client() {
    return client(false);
  }

  public static OkHttpClient client(long connectTimeout, long readTimeout, TimeUnit unit) {
    return client(
        clientBuilder().connectTimeout(connectTimeout, unit).readTimeout(readTimeout, unit), false);
  }

  public static OkHttpClient client(final boolean followRedirects) {
    return client(clientBuilder(), followRedirects);
  }

  static OkHttpClient client(final OkHttpClient.Builder builder, final boolean followRedirects) {
    return builder.followRedirects(followRedirects).build();
  }

  public static OkHttpClient client(
      final TestHttpServer server, final ProxySelector proxySelector) {
    return clientBuilder()
        .sslSocketFactory(server.sslContext.getSocketFactory(), server.getTrustManager())
        .hostnameVerifier(server.getHostnameVerifier())
        .proxySelector(proxySelector)
        .build();
  }

  public static <E> CookieJar cookieJar(Function<HttpUrl, E> cookieKey) {
    return new CustomCookieJar<>(cookieKey);
  }

  public static CookieJar cookieJar() {
    return cookieJar(HttpUrl::host);
  }

  private static class CustomCookieJar<E> implements CookieJar {

    private final ConcurrentHashMap<E, List<Cookie>> cookies;
    private final Function<HttpUrl, E> key;

    private CustomCookieJar(final Function<HttpUrl, E> key) {
      this.key = key;
      cookies = new ConcurrentHashMap<>();
    }

    @Override
    public void saveFromResponse(final HttpUrl httpUrl, final List<Cookie> list) {
      cookies.computeIfAbsent(key.apply(httpUrl), k -> new ArrayList<>()).addAll(list);
    }

    @Override
    public List<Cookie> loadForRequest(final HttpUrl httpUrl) {
      return cookies.getOrDefault(key.apply(httpUrl), Collections.emptyList());
    }
  }
}
