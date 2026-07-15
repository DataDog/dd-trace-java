package com.datadog.featureflag;

import static datadog.communication.http.OkHttpUtils.prepareRequest;
import static datadog.trace.util.AgentThreadFactory.AgentThread.FEATURE_FLAG_CONFIGURATION_POLLER;
import static datadog.trace.util.Strings.isBlank;

import datadog.communication.http.OkHttpUtils;
import datadog.logging.RatelimitedLogger;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.util.AgentThreadFactory;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import javax.annotation.Nullable;
import okhttp3.Call;
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
  private static final int MINUTES_BETWEEN_WARNINGS = 5;
  private static final long FIRST_RETRY_MIN_MILLIS = 2_000;
  private static final long FIRST_RETRY_MAX_MILLIS = 10_000;
  private static final long SECOND_RETRY_MIN_MILLIS = 5_000;
  private static final long SECOND_RETRY_MAX_MILLIS = 30_000;
  private static final double RETRY_JITTER = 0.2;

  private final HttpUrl endpoint;
  private final Config config;
  private final long pollIntervalMillis;
  private final UfcHttpClient client;
  private final ScheduledExecutorService executor;
  private final RetrySleeper retrySleeper;
  private final DoubleSupplier jitter;
  private final RatelimitedLogger ratelimitedLogger;
  private final Object lifecycleLock = new Object();
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
        Executors.newSingleThreadScheduledExecutor(
            new AgentThreadFactory(FEATURE_FLAG_CONFIGURATION_POLLER)),
        TimeUnit.MILLISECONDS::sleep,
        () -> ThreadLocalRandom.current().nextDouble(1 - RETRY_JITTER, 1 + RETRY_JITTER),
        new RatelimitedLogger(LOGGER, MINUTES_BETWEEN_WARNINGS, TimeUnit.MINUTES));
  }

  AgentlessConfigurationSource(
      final HttpUrl endpoint,
      final Config config,
      final long pollIntervalMillis,
      final UfcHttpClient client,
      final ScheduledExecutorService executor) {
    this(
        endpoint,
        config,
        pollIntervalMillis,
        client,
        executor,
        TimeUnit.MILLISECONDS::sleep,
        () -> 1.0,
        new RatelimitedLogger(LOGGER, MINUTES_BETWEEN_WARNINGS, TimeUnit.MINUTES));
  }

  AgentlessConfigurationSource(
      final HttpUrl endpoint,
      final Config config,
      final long pollIntervalMillis,
      final UfcHttpClient client,
      final ScheduledExecutorService executor,
      final RetrySleeper retrySleeper,
      final DoubleSupplier jitter) {
    this(
        endpoint,
        config,
        pollIntervalMillis,
        client,
        executor,
        retrySleeper,
        jitter,
        new RatelimitedLogger(LOGGER, MINUTES_BETWEEN_WARNINGS, TimeUnit.MINUTES));
  }

  AgentlessConfigurationSource(
      final HttpUrl endpoint,
      final Config config,
      final long pollIntervalMillis,
      final UfcHttpClient client,
      final ScheduledExecutorService executor,
      final RetrySleeper retrySleeper,
      final DoubleSupplier jitter,
      final RatelimitedLogger ratelimitedLogger) {
    this.endpoint = endpoint;
    this.config = config;
    this.pollIntervalMillis = pollIntervalMillis;
    this.client = client;
    this.executor = executor;
    this.retrySleeper = retrySleeper;
    this.jitter = jitter;
    this.ratelimitedLogger = ratelimitedLogger;
  }

  @Override
  public void init() {
    synchronized (lifecycleLock) {
      if (closed || scheduledPoll != null) {
        return;
      }
      scheduledPoll =
          executor.scheduleWithFixedDelay(
              this::pollOnceSafely, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);
    }
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
    final ScheduledFuture<?> poll;
    synchronized (lifecycleLock) {
      if (closed) {
        return;
      }
      closed = true;
      poll = scheduledPoll;
      scheduledPoll = null;
    }
    if (poll != null) {
      poll.cancel(true);
    }
    client.cancel();
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
        if (closed) {
          return false;
        }
        if (isRetryableStatus(response.status)) {
          if (attempt < MAX_ATTEMPTS) {
            if (!waitBeforeRetry(attempt)) {
              return false;
            }
            continue;
          }
          ratelimitedLogger.warn(
              "Feature Flagging agentless endpoint failed after {} attempts with HTTP {}",
              MAX_ATTEMPTS,
              response.status);
          return false;
        }
        synchronized (lifecycleLock) {
          return !closed && apply(response);
        }
      } catch (final IOException e) {
        if (closed) {
          return false;
        }
        if (attempt == MAX_ATTEMPTS) {
          ratelimitedLogger.warn(
              "Feature Flagging agentless endpoint request failed after {} attempts",
              MAX_ATTEMPTS,
              e);
          return false;
        }
        if (!waitBeforeRetry(attempt)) {
          return false;
        }
      }
    }
  }

  private boolean waitBeforeRetry(final int attempt) {
    if (closed) {
      return false;
    }
    try {
      retrySleeper.sleep(retryDelayMillis(pollIntervalMillis, attempt, jitter.getAsDouble()));
      return !closed;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private boolean apply(final UfcHttpResponse response) {
    if (response.status == HttpURLConnection.HTTP_NOT_MODIFIED) {
      return true;
    }
    if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED
        || response.status == HttpURLConnection.HTTP_FORBIDDEN) {
      ratelimitedLogger.warn(
          "Feature Flagging agentless endpoint returned HTTP {}; verify DD_API_KEY is configured and valid",
          response.status);
      return false;
    }
    if (response.status != HttpURLConnection.HTTP_OK || response.body == null) {
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
    FeatureFlaggingGateway.dispatch(configuration);
    updateEtag(response.etag);
    return true;
  }

  private static boolean isRetryableStatus(final int status) {
    return status == HttpURLConnection.HTTP_CLIENT_TIMEOUT
        || status == 429
        || (status >= 500 && status <= 599);
  }

  private void updateEtag(final String nextEtag) {
    etag = isBlank(nextEtag) ? null : nextEtag;
  }

  static HttpUrl endpoint(final Config config) {
    final String configuredBaseUrl = config.getFeatureFlaggingConfigurationSourceAgentlessBaseUrl();
    return configuredBaseUrl == null
        ? datadogApiServerDistributionEndpoint(config)
        : endpointFromConfiguredBaseUrl(configuredBaseUrl);
  }

  private static HttpUrl endpointFromConfiguredBaseUrl(final String configuredBaseUrl) {
    final HttpUrl parsed = HttpUrl.parse(configuredBaseUrl.trim());
    if (parsed == null) {
      throw new IllegalArgumentException(
          "Invalid Feature Flagging HTTP configuration source URL: " + configuredBaseUrl);
    }
    if ("/".equals(parsed.encodedPath()) || parsed.encodedPath().isEmpty()) {
      return parsed
          .newBuilder()
          .addPathSegments(DATADOG_API_SERVER_DISTRIBUTION_PATH.substring(1))
          .build();
    }
    return parsed;
  }

  private static HttpUrl datadogApiServerDistributionEndpoint(final Config config) {
    final HttpUrl.Builder endpoint =
        new HttpUrl.Builder()
            .scheme("https")
            .host("api." + config.getSite())
            .addPathSegments(DATADOG_API_SERVER_DISTRIBUTION_PATH.substring(1));
    final String env = config.getEnv();
    if (env != null && !env.isEmpty()) {
      endpoint.addQueryParameter("dd_env", env);
    }
    return endpoint.build();
  }

  static long millis(final int seconds) {
    return TimeUnit.SECONDS.toMillis(seconds);
  }

  static long retryDelayMillis(
      final long pollIntervalMillis, final int attempt, final double jitter) {
    final long baseDelay;
    if (attempt == 1) {
      baseDelay = clamp(pollIntervalMillis / 6, FIRST_RETRY_MIN_MILLIS, FIRST_RETRY_MAX_MILLIS);
    } else if (attempt == 2) {
      baseDelay = clamp(pollIntervalMillis / 3, SECOND_RETRY_MIN_MILLIS, SECOND_RETRY_MAX_MILLIS);
    } else {
      throw new IllegalArgumentException("Unsupported Feature Flagging retry attempt: " + attempt);
    }
    return Math.max(1, Math.round(baseDelay * jitter));
  }

  private static long clamp(final long value, final long minimum, final long maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  interface UfcHttpClient {
    UfcHttpResponse fetch(HttpUrl endpoint, Config config, String etag) throws IOException;

    void cancel();
  }

  interface RetrySleeper {
    void sleep(long delayMillis) throws InterruptedException;
  }

  static final class UfcHttpResponse {
    final int status;
    @Nullable final String etag;
    @Nullable final byte[] body;

    UfcHttpResponse(final int status, @Nullable final String etag, @Nullable final byte[] body) {
      this.status = status;
      this.etag = etag;
      this.body = body;
    }
  }

  static final class OkHttpUfcHttpClient implements UfcHttpClient {
    private final OkHttpClient httpClient;
    private final AtomicReference<Call> activeCall = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

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
      final Call call = httpClient.newCall(request);
      if (!activeCall.compareAndSet(null, call)) {
        throw new IllegalStateException("Feature Flagging HTTP request already in flight");
      }
      if (cancelled.get()) {
        call.cancel();
      }
      try {
        final Response response = call.execute();
        try (ResponseBody responseBody = response.body()) {
          final byte[] body = responseBody != null ? responseBody.bytes() : null;
          return new UfcHttpResponse(response.code(), response.header("ETag"), body);
        }
      } finally {
        activeCall.compareAndSet(call, null);
      }
    }

    @Override
    public void cancel() {
      cancelled.set(true);
      final Call call = activeCall.get();
      if (call != null) {
        call.cancel();
      }
    }
  }
}
