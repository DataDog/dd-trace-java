package com.datadog.openfeature.internal.delivery;

import com.datadog.openfeature.internal.ConfigurationService;
import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Polls the flag configuration from the Datadog managed CDN, or a custom HTTP endpoint. */
public final class CdnConfigurationSource implements ConfigurationService {
  private static final Logger LOGGER = LoggerFactory.getLogger(CdnConfigurationSource.class);

  static final String UFC_RULES_BASED_SERVER_PATH =
      "/api/v2/feature-flagging/config/rules-based/server";
  static final int MAX_ATTEMPTS = 3;
  private static final long FIRST_RETRY_MIN_MILLIS = 2_000;
  private static final long FIRST_RETRY_MAX_MILLIS = 10_000;
  private static final long SECOND_RETRY_MIN_MILLIS = 5_000;
  private static final long SECOND_RETRY_MAX_MILLIS = 30_000;
  private static final double RETRY_JITTER = 0.2;
  private static final long WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);

  private final URI endpoint;
  @Nullable private final String apiKey;
  private final long pollIntervalMillis;
  private final Duration requestTimeout;
  private final HttpClient httpClient;
  private final ScheduledExecutorService executor;
  private final RetrySleeper retrySleeper;
  private final DoubleSupplier jitter;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean polling = new AtomicBoolean();
  private volatile Consumer<ServerConfiguration> listener;
  private volatile boolean closed;
  private volatile boolean started;
  private volatile ScheduledFuture<?> scheduledPoll;
  private volatile Thread pollingThread;
  private volatile String etag;
  private long lastWarningNanos;

  public CdnConfigurationSource(final Settings settings, final RuntimeServices services) {
    this(
        endpoint(settings),
        isManagedEndpoint(settings) ? settings.apiKey() : null,
        TimeUnit.SECONDS.toMillis(settings.agentlessPollIntervalSeconds()),
        Duration.ofSeconds(settings.agentlessRequestTimeoutSeconds()),
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(settings.agentlessRequestTimeoutSeconds()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        Executors.newSingleThreadScheduledExecutor(
            task -> services.newThread("configuration", task)),
        TimeUnit.MILLISECONDS::sleep,
        () -> ThreadLocalRandom.current().nextDouble(1 - RETRY_JITTER, 1 + RETRY_JITTER));
  }

  CdnConfigurationSource(
      final URI endpoint,
      @Nullable final String apiKey,
      final long pollIntervalMillis,
      final Duration requestTimeout,
      final HttpClient httpClient,
      final ScheduledExecutorService executor,
      final RetrySleeper retrySleeper,
      final DoubleSupplier jitter) {
    this.endpoint = endpoint;
    this.apiKey = apiKey;
    this.pollIntervalMillis = pollIntervalMillis;
    this.requestTimeout = requestTimeout;
    this.httpClient = httpClient;
    this.executor = executor;
    this.retrySleeper = retrySleeper;
    this.jitter = jitter;
  }

  @Override
  public void start(final Consumer<ServerConfiguration> listener) {
    synchronized (this.lifecycleLock) {
      if (this.closed || this.started) {
        return;
      }
      this.started = true;
      this.listener = listener;
    }
    // Complete the first poll cycle on the calling thread so provider initialization observes a
    // successful retry before it checks whether configuration is ready.
    pollOnceSafely();
    synchronized (this.lifecycleLock) {
      if (!this.closed) {
        this.scheduledPoll =
            this.executor.scheduleWithFixedDelay(
                this::pollOnceSafely,
                this.pollIntervalMillis,
                this.pollIntervalMillis,
                TimeUnit.MILLISECONDS);
      }
    }
  }

  @Override
  public void close() {
    final ScheduledFuture<?> poll;
    synchronized (this.lifecycleLock) {
      if (this.closed) {
        return;
      }
      this.closed = true;
      poll = this.scheduledPoll;
      this.scheduledPoll = null;
    }
    if (poll != null) {
      poll.cancel(true);
    }
    final Thread activePollingThread = this.pollingThread;
    if (activePollingThread != null) {
      activePollingThread.interrupt();
    }
    this.executor.shutdownNow();
  }

  /** Test seam: sets the configuration listener without starting the polling. */
  void setListenerForTest(final Consumer<ServerConfiguration> listener) {
    this.listener = listener;
  }

  /**
   * Polls the endpoint once.
   *
   * @return {@code true} if the configuration was applied or is unchanged, {@code false} otherwise.
   */
  boolean pollOnce() {
    if (this.closed || !this.polling.compareAndSet(false, true)) {
      return false;
    }
    this.pollingThread = Thread.currentThread();
    try {
      return fetchAndApply();
    } finally {
      this.pollingThread = null;
      this.polling.set(false);
    }
  }

  private void pollOnceSafely() {
    try {
      pollOnce();
    } catch (final RuntimeException e) {
      LOGGER.debug("Unexpected error while polling the Feature Flags configuration", e);
    }
  }

  private boolean fetchAndApply() {
    final HttpResponse<byte[]> response;
    try {
      response = fetchWithRetries();
    } catch (final IOException e) {
      if (!this.closed) {
        warn("Feature Flags configuration request failed after {} attempts", MAX_ATTEMPTS, e);
      }
      return false;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    if (this.closed) {
      return false;
    }
    final int status = response.statusCode();
    if (isRetryableStatus(status)) {
      warn(
          "Feature Flags configuration endpoint failed after {} attempts with HTTP {}",
          MAX_ATTEMPTS,
          status);
      return false;
    }
    synchronized (this.lifecycleLock) {
      return !this.closed && apply(response);
    }
  }

  private boolean apply(final HttpResponse<byte[]> response) {
    final int status = response.statusCode();
    if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
      return true;
    }
    if (status == HttpURLConnection.HTTP_UNAUTHORIZED
        || status == HttpURLConnection.HTTP_FORBIDDEN) {
      warn(
          "Feature Flags configuration endpoint returned HTTP {}; verify endpoint authentication",
          status);
      return false;
    }
    if (status != HttpURLConnection.HTTP_OK || response.body() == null) {
      return false;
    }
    final ServerConfiguration configuration;
    try {
      configuration = UniversalFlagConfigParser.parseJsonApi(Gzip.decode(response));
    } catch (final IOException | RuntimeException e) {
      LOGGER.debug("Feature Flags configuration endpoint returned a malformed payload", e);
      return false;
    }
    if (configuration == null) {
      return false;
    }
    this.listener.accept(configuration);
    final String nextEtag = response.headers().firstValue("ETag").orElse(null);
    this.etag = nextEtag == null || nextEtag.trim().isEmpty() ? null : nextEtag;
    return true;
  }

  private HttpResponse<byte[]> fetchWithRetries() throws IOException, InterruptedException {
    final HttpRequest request = buildRequest();
    int attempt = 0;
    while (true) {
      attempt++;
      try {
        final HttpResponse<byte[]> response =
            this.httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (!isRetryableStatus(response.statusCode()) || attempt == MAX_ATTEMPTS || this.closed) {
          return response;
        }
      } catch (final IOException e) {
        if (attempt == MAX_ATTEMPTS || this.closed || Thread.currentThread().isInterrupted()) {
          throw e;
        }
      }
      this.retrySleeper.sleep(
          retryDelayMillis(this.pollIntervalMillis, attempt, this.jitter.getAsDouble()));
    }
  }

  private HttpRequest buildRequest() {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder(this.endpoint)
            .timeout(this.requestTimeout)
            .header("Accept-Encoding", "gzip");
    Headers.addMetadata(request);
    if (this.apiKey != null) {
      request.header("DD-API-KEY", this.apiKey);
    }
    final String currentEtag = this.etag;
    if (currentEtag != null) {
      request.header("If-None-Match", currentEtag);
    }
    return request.GET().build();
  }

  private synchronized void warn(final String message, final Object... arguments) {
    final long now = System.nanoTime();
    if (this.lastWarningNanos == 0 || now - this.lastWarningNanos >= WARNING_INTERVAL_NANOS) {
      this.lastWarningNanos = now;
      LOGGER.warn(message, arguments);
    } else {
      LOGGER.debug(message, arguments);
    }
  }

  static boolean isRetryableStatus(final int status) {
    return status == HttpURLConnection.HTTP_CLIENT_TIMEOUT
        || status == 429
        || (status >= 500 && status <= 599);
  }

  static URI endpoint(final Settings settings) {
    final String configuredBaseUrl = settings.agentlessBaseUrl();
    if (configuredBaseUrl == null) {
      final String env = settings.env();
      return URI.create(
          "https://"
              + managedHost(settings)
              + UFC_RULES_BASED_SERVER_PATH
              + (env == null ? "" : "?dd_env=" + URLEncoder.encode(env, StandardCharsets.UTF_8)));
    }
    final URI parsed;
    try {
      parsed = URI.create(configuredBaseUrl);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Invalid Feature Flags configuration source URL: " + configuredBaseUrl, e);
    }
    if (parsed.getScheme() == null || parsed.getHost() == null) {
      throw new IllegalArgumentException(
          "Invalid Feature Flags configuration source URL: " + configuredBaseUrl);
    }
    final String path = parsed.getRawPath();
    if (path == null || path.isEmpty() || "/".equals(path)) {
      return parsed.resolve(UFC_RULES_BASED_SERVER_PATH);
    }
    return parsed;
  }

  private static boolean isManagedEndpoint(final Settings settings) {
    return settings.agentlessBaseUrl() == null;
  }

  private static String managedHost(final Settings settings) {
    return "ufc-server.ff-cdn." + settings.site();
  }

  static long retryDelayMillis(
      final long pollIntervalMillis, final int attempt, final double jitter) {
    final long baseDelay;
    if (attempt == 1) {
      baseDelay = clamp(pollIntervalMillis / 6, FIRST_RETRY_MIN_MILLIS, FIRST_RETRY_MAX_MILLIS);
    } else if (attempt == 2) {
      baseDelay = clamp(pollIntervalMillis / 3, SECOND_RETRY_MIN_MILLIS, SECOND_RETRY_MAX_MILLIS);
    } else {
      throw new IllegalArgumentException("Unsupported Feature Flags retry attempt: " + attempt);
    }
    return Math.max(1, Math.round(baseDelay * jitter));
  }

  private static long clamp(final long value, final long minimum, final long maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  @FunctionalInterface
  interface RetrySleeper {
    void sleep(long delayMillis) throws InterruptedException;
  }
}
