package com.datadog.openfeature.internal.delivery;

import static com.datadog.openfeature.internal.delivery.Retries.isRetryableStatus;

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
import java.util.concurrent.CountDownLatch;
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
  // The Agent cuts a service to this many bytes of UTF-8.
  private static final int MAX_SERVICE_BYTES = 100;

  private final URI endpoint;
  @Nullable private final String apiKey;
  @Nullable private final String service;
  private final long pollIntervalMillis;
  private final Duration requestTimeout;
  private final HttpClient httpClient;
  private final ScheduledExecutorService executor;
  private final Retries.Sleeper retrySleeper;
  private final DoubleSupplier jitter;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean polling = new AtomicBoolean();
  private final CountDownLatch closedSignal = new CountDownLatch(1);
  private HttpCall<byte[]> activeCall;
  private volatile Consumer<ServerConfiguration> listener;
  private volatile boolean closed;
  private volatile boolean started;
  private volatile ScheduledFuture<?> scheduledPoll;
  private volatile String etag;
  private long lastWarningNanos;

  public CdnConfigurationSource(final Settings settings, final RuntimeServices services) {
    this(
        endpoint(settings),
        isManagedEndpoint(settings) ? settings.apiKey() : null,
        usageService(settings.untrimmedService()),
        TimeUnit.SECONDS.toMillis(settings.agentlessPollIntervalSeconds()),
        Duration.ofSeconds(settings.agentlessRequestTimeoutSeconds()),
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(settings.agentlessRequestTimeoutSeconds()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        Executors.newSingleThreadScheduledExecutor(
            task -> services.newThread("configuration", task)),
        null,
        () -> ThreadLocalRandom.current().nextDouble(1 - RETRY_JITTER, 1 + RETRY_JITTER));
  }

  CdnConfigurationSource(
      final URI endpoint,
      @Nullable final String apiKey,
      @Nullable final String service,
      final long pollIntervalMillis,
      final Duration requestTimeout,
      final HttpClient httpClient,
      final ScheduledExecutorService executor,
      @Nullable final Retries.Sleeper retrySleeper,
      final DoubleSupplier jitter) {
    this.endpoint = endpoint;
    this.apiKey = apiKey;
    this.service = service;
    this.pollIntervalMillis = pollIntervalMillis;
    this.requestTimeout = requestTimeout;
    this.httpClient = httpClient;
    this.executor = executor;
    this.retrySleeper =
        retrySleeper != null
            ? retrySleeper
            : delay -> this.closedSignal.await(delay, TimeUnit.MILLISECONDS);
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
    // Logged last: a diagnostic must not keep the polling from starting.
    LOGGER.debug(
        "Feature Flags agentless configuration source started: endpoint={}://{}{}, pollIntervalMillis={}, requestTimeout={}",
        // The origin only: the userinfo, the path and the query of a custom endpoint can carry a
        // credential.
        this.endpoint.getScheme(),
        this.endpoint.getHost(),
        this.endpoint.getPort() < 0 ? "" : ":" + this.endpoint.getPort(),
        this.pollIntervalMillis,
        this.requestTimeout);
  }

  @Override
  public void close() {
    final ScheduledFuture<?> poll;
    final HttpCall<byte[]> call;
    synchronized (this.lifecycleLock) {
      if (this.closed) {
        return;
      }
      this.closed = true;
      poll = this.scheduledPoll;
      this.scheduledPoll = null;
      call = this.activeCall;
      this.activeCall = null;
    }
    this.closedSignal.countDown();
    if (call != null) {
      call.cancel();
    }
    if (poll != null) {
      poll.cancel(true);
    }
    this.executor.shutdownNow();
    // Logged last: a diagnostic must not keep the source from being closed.
    LOGGER.debug("Feature Flags agentless configuration source stopped");
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
    try {
      return fetchAndApply();
    } finally {
      synchronized (this.lifecycleLock) {
        this.activeCall = null;
      }
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
      LOGGER.debug("Feature Flags agentless configuration unchanged (HTTP 304)");
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
      warn("Feature Flags configuration endpoint returned HTTP {}", status);
      return false;
    }
    final ServerConfiguration configuration;
    try {
      configuration = UniversalFlagConfigParser.parseJsonApi(Gzip.decode(response));
    } catch (final IOException | RuntimeException e) {
      LOGGER.debug(
          "Feature Flags configuration endpoint returned a malformed payload ({}); retaining the current configuration",
          e.getClass().getSimpleName(),
          e);
      return false;
    }
    if (configuration == null) {
      LOGGER.debug(
          "Feature Flags configuration endpoint returned no flag data; retaining the current configuration");
      return false;
    }
    this.listener.accept(configuration);
    final String nextEtag = response.headers().firstValue("ETag").orElse(null);
    this.etag = nextEtag == null || nextEtag.trim().isEmpty() ? null : nextEtag;
    LOGGER.debug("Feature Flags agentless configuration applied: etag={}", this.etag);
    return true;
  }

  private HttpResponse<byte[]> fetchWithRetries() throws IOException, InterruptedException {
    final HttpRequest request = buildRequest();
    return Retries.send(
        () -> {
          synchronized (this.lifecycleLock) {
            if (this.closed) {
              throw new IOException("Feature Flags configuration source is closed");
            }
            this.activeCall =
                new HttpCall<>(this.httpClient, request, HttpResponse.BodyHandlers.ofByteArray());
            return this.activeCall;
          }
        },
        (response, attempt) -> {
          if (response != null && !isRetryableStatus(response.statusCode())) {
            return -1;
          }
          final long delayMillis =
              retryDelayMillis(this.pollIntervalMillis, attempt, this.jitter.getAsDouble());
          LOGGER.debug(
              "Feature Flags agentless configuration poll attempt {}/{} {}; retrying in {} ms",
              attempt,
              MAX_ATTEMPTS,
              response == null ? "failed" : "returned HTTP " + response.statusCode(),
              delayMillis);
          return delayMillis;
        },
        MAX_ATTEMPTS,
        this.retrySleeper,
        () -> this.closed);
  }

  private HttpRequest buildRequest() {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder(this.endpoint)
            .timeout(this.requestTimeout)
            .header("Accept-Encoding", "gzip");
    Headers.addMetadata(request);
    Headers.addClientLibrary(request);
    if (this.apiKey != null) {
      request.header("DD-API-KEY", this.apiKey);
    }
    if (this.service != null) {
      // The CDN attributes the usage of this request to this service.
      request.header("DD-Service", this.service);
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
    final String scheme = parsed.getScheme();
    if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
        || parsed.getHost() == null) {
      throw new IllegalArgumentException(
          "Invalid Feature Flags configuration source URL: " + configuredBaseUrl);
    }
    final String path = parsed.getRawPath();
    if (path == null || path.isEmpty() || "/".equals(path)) {
      final String query = parsed.getRawQuery();
      return parsed.resolve(
          query == null ? UFC_RULES_BASED_SERVER_PATH : UFC_RULES_BASED_SERVER_PATH + "?" + query);
    }
    return parsed;
  }

  private static boolean isManagedEndpoint(final Settings settings) {
    return settings.agentlessBaseUrl() == null;
  }

  /**
   * Selects the value of the DD-Service header. The value is the service name that APM shows, so
   * the usage and APM show the same name. The value is {@code null} when that name is empty or is
   * not printable ASCII, for example {@code café-api} or {@code 支付}.
   *
   * <p>The CDN decides which values it attributes usage to, so the SDK does not check their format.
   * The SDK checks only for ASCII: {@code HttpRequest.Builder} throws on a value like {@code 支付},
   * and then every poll fails.
   */
  @Nullable
  private static String usageService(@Nullable final String service) {
    if (service == null || service.isEmpty()) {
      return null;
    }
    final String normalized = normalizeService(service);
    if (isPrintableAscii(normalized)) {
      return normalized;
    }
    LOGGER.debug(
        "Feature Flags: service {} has no ASCII APM name; DD-Service header not sent", service);
    return null;
  }

  /** Returns {@code true} if the value is not empty and has only the characters 0x21 to 0x7E. */
  private static boolean isPrintableAscii(final String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if (c < 0x21 || c > 0x7E) {
        return false;
      }
    }
    return true;
  }

  /**
   * Normalizes a service name with the rules of {@code NormalizeService} in the Datadog Agent
   * (datadog-agent pkg/trace/traceutil/normalize, v0.82.3). The result is the name that APM shows.
   * The SDK does not depend on internal-api, so it cannot call {@code
   * TraceUtils.normalizeServiceName}. That helper also follows older Agent rules.
   *
   * <ul>
   *   <li>Cuts the name to 100 bytes of UTF-8, and does not split a character.
   *   <li>Lowercases the ASCII letters.
   *   <li>Keeps {@code a-z}, {@code 0-9}, {@code :}, {@code .}, {@code /}, {@code -}, and the
   *       non-ASCII letters and decimal digits.
   *   <li>Replaces each run of other characters with one {@code _}, and removes a run at the end.
   * </ul>
   */
  private static String normalizeService(final String service) {
    final StringBuilder normalized =
        new StringBuilder(Math.min(service.length(), MAX_SERVICE_BYTES));
    boolean underscorePending = false;
    int bytes = 0;
    for (int i = 0; i < service.length(); ) {
      int c = service.codePointAt(i);
      bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4;
      if (bytes > MAX_SERVICE_BYTES) {
        break;
      }
      i += Character.charCount(c);
      if (c >= 'A' && c <= 'Z') {
        c += 'a' - 'A';
      }
      if (isServiceCharacter(c)) {
        if (underscorePending) {
          normalized.append('_');
          underscorePending = false;
        }
        normalized.appendCodePoint(c);
      } else {
        underscorePending = true;
      }
    }
    return normalized.toString();
  }

  private static boolean isServiceCharacter(final int c) {
    if (c < 0x80) {
      return (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || c == ':'
          || c == '.'
          || c == '/'
          || c == '-';
    }
    // Like unicode.IsLetter and unicode.IsDigit in Go: the letters and the decimal digits (Nd).
    return Character.isLetterOrDigit(c);
  }

  private static String managedHost(final Settings settings) {
    return SiteHost.of("ufc-server.ff-cdn", settings.site());
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
}
