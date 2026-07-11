package com.datadog.featureflag;

import static datadog.communication.http.OkHttpUtils.prepareRequest;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AgentlessConfigurationSource implements ConfigurationSourceService {
  private static final Logger LOGGER = LoggerFactory.getLogger(AgentlessConfigurationSource.class);

  // TODO before merge: confirm the final backend route with the server-distribution API owners.
  private static final String DATADOG_API_SERVER_DISTRIBUTION_PATH =
      "/api/v2/feature-flagging/config/server-distribution";
  private static final int MAX_ATTEMPTS = 3;

  private final HttpUrl endpoint;
  private final Config config;
  private final long pollIntervalMillis;
  private final UfcHttpClient client;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean polling = new AtomicBoolean();
  private volatile boolean closed;
  private volatile ScheduledFuture<?> scheduledPoll;
  private volatile String etag;

  AgentlessConfigurationSource(final Config config) {
    this(config, endpoint(config));
  }

  private AgentlessConfigurationSource(final Config config, final HttpUrl endpoint) {
    this(
        endpoint,
        config,
        millis(config.getFeatureFlaggingConfigurationSourcePollIntervalSeconds()),
        new OkHttpUfcHttpClient(
            OkHttpUtils.buildHttpClient(
                endpoint,
                millis(config.getFeatureFlaggingConfigurationSourceRequestTimeoutSeconds()))),
        Executors.newSingleThreadScheduledExecutor(new UfcHttpThreadFactory()));
  }

  AgentlessConfigurationSource(
      final HttpUrl endpoint,
      final Config config,
      final long pollIntervalMillis,
      final UfcHttpClient client,
      final ScheduledExecutorService executor) {
    this.endpoint = endpoint;
    this.config = config;
    this.pollIntervalMillis = pollIntervalMillis;
    this.client = client;
    this.executor = executor;
  }

  @Override
  public void init() {
    if (closed) {
      return;
    }
    scheduledPoll =
        executor.scheduleWithFixedDelay(
            this::pollOnceSafely, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);
  }

  boolean pollOnce() {
    if (closed || !polling.compareAndSet(false, true)) {
      return false;
    }
    try {
      return fetchAndApply();
    } finally {
      polling.set(false);
    }
  }

  @Override
  public void close() {
    closed = true;
    if (scheduledPoll != null) {
      scheduledPoll.cancel(true);
      scheduledPoll = null;
    }
    executor.shutdownNow();
  }

  private void pollOnceSafely() {
    try {
      pollOnce();
    } catch (final RuntimeException e) {
      LOGGER.debug("Unexpected error while polling Feature Flagging HTTP configuration source", e);
    }
  }

  private boolean fetchAndApply() {
    for (int attempt = 1; ; attempt++) {
      try {
        final UfcHttpResponse response = client.fetch(endpoint, config, etag);
        if (isRetryableStatus(response.status) && attempt < MAX_ATTEMPTS) {
          continue;
        }
        return apply(response);
      } catch (final IOException e) {
        if (attempt == MAX_ATTEMPTS) {
          LOGGER.debug("Feature Flagging HTTP configuration source request failed", e);
          return false;
        }
      }
    }
  }

  private boolean apply(final UfcHttpResponse response) {
    if (response.status == HttpURLConnection.HTTP_NOT_MODIFIED) {
      updateEtag(response.etag);
      return true;
    }
    if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED
        || response.status == HttpURLConnection.HTTP_FORBIDDEN
        || response.status != HttpURLConnection.HTTP_OK
        || response.body == null) {
      return false;
    }
    final ServerConfiguration configuration;
    try {
      configuration =
          RemoteConfigServiceImpl.UniversalFlagConfigDeserializer.INSTANCE.deserialize(
              response.body);
    } catch (final IOException | RuntimeException e) {
      LOGGER.debug("Feature Flagging HTTP configuration source returned malformed UFC payload", e);
      return false;
    }
    if (configuration == null) {
      return false;
    }
    updateEtag(response.etag);
    FeatureFlaggingGateway.dispatch(configuration);
    return true;
  }

  private static boolean isRetryableStatus(final int status) {
    return status == HttpURLConnection.HTTP_CLIENT_TIMEOUT
        || status == 429
        || (status >= 500 && status <= 599);
  }

  private void updateEtag(final String nextEtag) {
    if (nextEtag != null && !nextEtag.trim().isEmpty()) {
      etag = nextEtag;
    }
  }

  static HttpUrl endpoint(final Config config) {
    final String configuredBaseUrl = config.getFeatureFlaggingConfigurationSourceAgentlessBaseUrl();
    final String endpoint =
        configuredBaseUrl == null
            ? datadogApiServerDistributionEndpoint(config)
            : endpointFromConfiguredBaseUrl(configuredBaseUrl);
    final HttpUrl parsed = HttpUrl.parse(endpoint);
    if (parsed == null) {
      throw new IllegalArgumentException(
          "Invalid Feature Flagging HTTP configuration source URL: " + endpoint);
    }
    return parsed;
  }

  private static String endpointFromConfiguredBaseUrl(final String configuredBaseUrl) {
    final HttpUrl parsed = HttpUrl.parse(configuredBaseUrl.trim());
    if (parsed == null) {
      throw new IllegalArgumentException(
          "Invalid Feature Flagging HTTP configuration source URL: " + configuredBaseUrl);
    }
    if ("/".equals(parsed.encodedPath()) || parsed.encodedPath().isEmpty()) {
      return parsed
          .newBuilder()
          .addPathSegments(DATADOG_API_SERVER_DISTRIBUTION_PATH.substring(1))
          .build()
          .toString();
    }
    return parsed.toString();
  }

  private static String datadogApiServerDistributionEndpoint(final Config config) {
    final StringBuilder endpoint =
        new StringBuilder("https://api.")
            .append(config.getSite().toLowerCase(Locale.ROOT))
            .append(DATADOG_API_SERVER_DISTRIBUTION_PATH);
    final String env = config.getEnv();
    if (env != null && !env.isEmpty()) {
      endpoint.append("?dd_env=").append(urlEncode(env));
    }
    return endpoint.toString();
  }

  private static String urlEncode(final String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (final IOException e) {
      throw new IllegalArgumentException("Unable to encode Feature Flagging environment", e);
    }
  }

  static long millis(final int seconds) {
    return Math.max(1L, seconds * 1000L);
  }

  interface UfcHttpClient {
    UfcHttpResponse fetch(HttpUrl endpoint, Config config, String etag) throws IOException;
  }

  static final class UfcHttpResponse {
    final int status;
    final String etag;
    final byte[] body;

    UfcHttpResponse(final int status, final String etag, final byte[] body) {
      this.status = status;
      this.etag = etag;
      this.body = body;
    }
  }

  static final class OkHttpUfcHttpClient implements UfcHttpClient {
    private final OkHttpClient httpClient;

    OkHttpUfcHttpClient(final OkHttpClient httpClient) {
      this.httpClient = httpClient;
    }

    @Override
    public UfcHttpResponse fetch(final HttpUrl endpoint, final Config config, final String etag)
        throws IOException {
      final Map<String, String> headers = new HashMap<>();
      if (etag != null) {
        headers.put("If-None-Match", etag);
      }
      final Request request = prepareRequest(endpoint, headers, config, true).get().build();
      try (Response response = httpClient.newCall(request).execute()) {
        final ResponseBody responseBody = response.body();
        return new UfcHttpResponse(response.code(), response.header("ETag"), responseBody.bytes());
      }
    }
  }

  static final class UfcHttpThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(final Runnable runnable) {
      final Thread thread = new Thread(runnable, "dd-feature-flagging-http-poller");
      thread.setDaemon(true);
      return thread;
    }
  }
}
