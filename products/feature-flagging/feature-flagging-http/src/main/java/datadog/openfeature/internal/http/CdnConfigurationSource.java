package datadog.openfeature.internal.http;

import datadog.openfeature.internal.core.ApplyResult;
import datadog.openfeature.internal.core.ConfigurationSink;
import datadog.openfeature.internal.core.ConfigurationSource;
import datadog.openfeature.internal.core.SourceStatus;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;

/** Java 11 HTTP client source for CDN-backed UFC delivery. */
public final class CdnConfigurationSource implements ConfigurationSource {

  static final int MAX_ATTEMPTS = 3;
  private static final double RETRY_JITTER = 0.2;

  private final HttpConfigurationOptions options;
  private final ConfigurationSink sink;
  private final Transport transport;
  private final ScheduledExecutorService executor;
  private final Sleeper sleeper;
  private final DoubleSupplier jitter;
  private final AtomicBoolean polling = new AtomicBoolean();
  private final Object lifecycleLock = new Object();
  private volatile SourceStatus status = SourceStatus.NEW;
  private volatile boolean closed;
  private volatile boolean started;
  private volatile ScheduledFuture<?> scheduledPoll;
  private volatile String etag;

  public CdnConfigurationSource(
      final HttpConfigurationOptions options, final ConfigurationSink sink) {
    this(
        options,
        sink,
        new LazyTransport(),
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              final Thread thread = new Thread(runnable, "dd-openfeature-cdn-poller");
              thread.setDaemon(true);
              return thread;
            }),
        TimeUnit.MILLISECONDS::sleep,
        () -> ThreadLocalRandom.current().nextDouble(1 - RETRY_JITTER, 1 + RETRY_JITTER));
  }

  CdnConfigurationSource(
      final HttpConfigurationOptions options,
      final ConfigurationSink sink,
      final Transport transport,
      final ScheduledExecutorService executor,
      final Sleeper sleeper,
      final DoubleSupplier jitter) {
    this.options = options;
    this.sink = sink;
    this.transport = transport;
    this.executor = executor;
    this.sleeper = sleeper;
    this.jitter = jitter;
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (closed || started) {
        return;
      }
      started = true;
      status = SourceStatus.STARTING;
    }

    synchronized (lifecycleLock) {
      if (!closed) {
        scheduledPoll =
            executor.scheduleWithFixedDelay(
                this::pollOnceSafely, 0, options.pollInterval.toMillis(), TimeUnit.MILLISECONDS);
      }
    }
  }

  public boolean pollOnce() {
    if (closed || !polling.compareAndSet(false, true)) {
      return false;
    }
    try {
      final boolean success = fetchWithRetry();
      if (!closed) {
        status = success ? SourceStatus.READY : SourceStatus.ERROR;
      }
      return success;
    } finally {
      polling.set(false);
    }
  }

  @Override
  public SourceStatus status() {
    return status;
  }

  @Override
  public void close() {
    final ScheduledFuture<?> poll;
    synchronized (lifecycleLock) {
      if (closed) {
        return;
      }
      closed = true;
      started = false;
      status = SourceStatus.CLOSED;
      poll = scheduledPoll;
      scheduledPoll = null;
    }
    if (poll != null) {
      poll.cancel(true);
    }
    transport.cancel();
    executor.shutdownNow();
  }

  private void pollOnceSafely() {
    try {
      pollOnce();
    } catch (final RuntimeException ignored) {
      if (!closed) {
        status = SourceStatus.ERROR;
      }
    }
  }

  private boolean fetchWithRetry() {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS && !closed; attempt++) {
      try {
        final TransportResponse response =
            transport.fetch(options, etag == null ? Collections.emptyMap() : etagHeader(etag));
        synchronized (lifecycleLock) {
          if (closed) {
            return false;
          }
          if (response.status == 304) {
            return true;
          }
          if (response.status == 200 && response.body != null) {
            final ApplyResult result = sink.apply(response.body);
            if (result == ApplyResult.ACCEPTED) {
              etag = blankToNull(response.etag);
              return true;
            }
            return false;
          }
        }
        if (!retryable(response.status) || attempt == MAX_ATTEMPTS) {
          return false;
        }
      } catch (final UfcResponseBodyReader.ResponseTooLargeException e) {
        return false;
      } catch (final IOException e) {
        if (attempt == MAX_ATTEMPTS || closed) {
          return false;
        }
      }

      try {
        sleeper.sleep(
            retryDelayMillis(options.pollInterval.toMillis(), attempt, jitter.getAsDouble()));
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  static long retryDelayMillis(
      final long pollIntervalMillis, final int attempt, final double jitter) {
    final long minimum = attempt == 1 ? 2_000 : 5_000;
    final long maximum = attempt == 1 ? 10_000 : 30_000;
    final long fraction = pollIntervalMillis / (attempt == 1 ? 6 : 3);
    return Math.max(1, Math.round(Math.max(minimum, Math.min(maximum, fraction)) * jitter));
  }

  private static boolean retryable(final int status) {
    return status == 408 || status == 429 || status >= 500 && status <= 599;
  }

  private static Map<String, String> etagHeader(final String etag) {
    final Map<String, String> headers = new LinkedHashMap<>();
    headers.put("If-None-Match", etag);
    return headers;
  }

  private static String blankToNull(final String value) {
    return value == null || value.trim().isEmpty() ? null : value;
  }

  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  interface Transport {
    TransportResponse fetch(HttpConfigurationOptions options, Map<String, String> headers)
        throws IOException;

    void cancel();
  }

  static final class TransportResponse {
    final int status;
    final String etag;
    final byte[] body;

    TransportResponse(final int status, final String etag, final byte[] body) {
      this.status = status;
      this.etag = etag;
      this.body = body;
    }
  }

  static final class Java11Transport implements Transport {
    private final ExecutorService executor;
    private final AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile HttpClient client;

    Java11Transport() {
      executor =
          Executors.newSingleThreadExecutor(
              runnable -> {
                final Thread thread = new Thread(runnable, "dd-openfeature-cdn-http");
                thread.setDaemon(true);
                return thread;
              });
      client =
          HttpClient.newBuilder()
              .executor(executor)
              .followRedirects(HttpClient.Redirect.NORMAL)
              .build();
    }

    @Override
    public TransportResponse fetch(
        final HttpConfigurationOptions options, final Map<String, String> headers)
        throws IOException {
      final HttpClient currentClient = client;
      if (cancelled.get() || currentClient == null) {
        throw new InterruptedIOException("Feature Flagging HTTP source is closed");
      }
      final HttpRequest.Builder request =
          HttpRequest.newBuilder(options.endpoint)
              .timeout(options.requestTimeout)
              .GET()
              .header("Datadog-Meta-Lang", "java")
              .header("Accept", "application/json")
              .header("Accept-Encoding", "gzip");
      if (options.managedEndpoint && options.apiKey != null && !options.apiKey.isEmpty()) {
        request.header("DD-API-KEY", options.apiKey);
      }
      headers.forEach(request::header);

      final CompletableFuture<java.net.http.HttpResponse<byte[]>> future;
      try {
        future =
            currentClient.sendAsync(request.build(), UfcResponseBodyReader.boundedBodyHandler());
      } catch (final RejectedExecutionException e) {
        if (cancelled.get()) {
          final InterruptedIOException closed =
              new InterruptedIOException("Feature Flagging HTTP source is closed");
          closed.initCause(e);
          throw closed;
        }
        throw e;
      }
      active.set(future);
      if (cancelled.get()) {
        future.cancel(true);
      }
      try {
        final java.net.http.HttpResponse<byte[]> response = future.get();
        return new TransportResponse(
            response.statusCode(),
            response.headers().firstValue("ETag").orElse(null),
            UfcResponseBodyReader.decode(
                response.body(), response.headers().firstValue("Content-Encoding").orElse(null)));
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("Feature Flagging HTTP request interrupted");
      } catch (final CancellationException e) {
        throw new InterruptedIOException("Feature Flagging HTTP request cancelled");
      } catch (final ExecutionException e) {
        final Throwable cause = unwrapCompletionFailure(e.getCause());
        if (cause instanceof CancellationException) {
          throw new InterruptedIOException("Feature Flagging HTTP request cancelled");
        }
        if (cause instanceof IOException) {
          throw (IOException) cause;
        }
        throw new IOException("Feature Flagging HTTP request failed", cause);
      } finally {
        active.compareAndSet(future, null);
      }
    }

    static Throwable unwrapCompletionFailure(final Throwable failure) {
      Throwable current = failure;
      while (current instanceof java.util.concurrent.CompletionException
          && current.getCause() != null) {
        current = current.getCause();
      }
      return current;
    }

    @Override
    public void cancel() {
      cancelled.set(true);
      client = null;
      final CompletableFuture<?> future = active.get();
      if (future != null) {
        future.cancel(true);
      }
      executor.shutdownNow();
    }

    boolean isTerminated() {
      return executor.isTerminated();
    }
  }

  static final class LazyTransport implements Transport {
    private Java11Transport delegate;
    private boolean cancelled;

    @Override
    public TransportResponse fetch(
        final HttpConfigurationOptions options, final Map<String, String> headers)
        throws IOException {
      final Java11Transport current;
      synchronized (this) {
        if (cancelled) {
          throw new InterruptedIOException("Feature Flagging HTTP source is closed");
        }
        if (delegate == null) {
          delegate = new Java11Transport();
        }
        current = delegate;
      }
      return current.fetch(options, headers);
    }

    @Override
    public void cancel() {
      final Java11Transport current;
      synchronized (this) {
        if (cancelled) {
          return;
        }
        cancelled = true;
        current = delegate;
        delegate = null;
      }
      if (current != null) {
        current.cancel();
      }
    }
  }
}
