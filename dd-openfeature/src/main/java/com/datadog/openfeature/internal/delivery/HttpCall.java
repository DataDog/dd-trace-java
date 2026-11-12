package com.datadog.openfeature.internal.delivery;

import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;

/** A cancellable HTTP attempt whose timeout includes response body consumption. */
final class HttpCall<T> {
  private final long startedNanos = System.nanoTime();
  private final long timeoutNanos;
  private final CompletableFuture<HttpResponse<T>> response;
  private volatile Flow.Subscription subscription;
  private volatile boolean cancelled;

  HttpCall(
      final HttpClient client,
      final HttpRequest request,
      final HttpResponse.BodyHandler<T> bodyHandler) {
    this.timeoutNanos = request.timeout().orElseThrow().toNanos();
    this.response =
        client.sendAsync(request, info -> new CancellableBodySubscriber(bodyHandler.apply(info)));
  }

  /**
   * Waits for the complete response.
   *
   * @return the response, including its consumed body.
   * @throws IOException if the request fails, times out, or is cancelled.
   * @throws InterruptedException if the caller is interrupted.
   */
  HttpResponse<T> execute() throws IOException, InterruptedException {
    try {
      return this.response.get(
          max(0, this.timeoutNanos - (System.nanoTime() - this.startedNanos)), NANOSECONDS);
    } catch (final TimeoutException e) {
      cancel();
      throw new HttpTimeoutException("HTTP response timed out");
    } catch (final InterruptedException e) {
      cancel();
      throw e;
    } catch (final CancellationException e) {
      throw new IOException("HTTP request cancelled", e);
    } catch (final ExecutionException e) {
      final Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      if (cause instanceof CancellationException) {
        throw new IOException("HTTP request cancelled", cause);
      }
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new IOException(cause);
    }
  }

  /** Cancels the active exchange and any response body subscription. */
  void cancel() {
    if (!this.cancelled && this.response.cancel(true)) {
      this.cancelled = true;
      final Flow.Subscription subscription = this.subscription;
      if (subscription != null) {
        subscription.cancel();
      }
    }
  }

  private void subscribe(final Flow.Subscription subscription) {
    this.subscription = subscription;
    if (this.cancelled) {
      subscription.cancel();
    }
  }

  private final class CancellableBodySubscriber implements HttpResponse.BodySubscriber<T> {
    private final HttpResponse.BodySubscriber<T> delegate;

    private CancellableBodySubscriber(final HttpResponse.BodySubscriber<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public CompletionStage<T> getBody() {
      return this.delegate.getBody();
    }

    @Override
    public void onSubscribe(final Flow.Subscription subscription) {
      this.delegate.onSubscribe(subscription);
      subscribe(subscription);
    }

    @Override
    public void onNext(final List<ByteBuffer> buffers) {
      this.delegate.onNext(buffers);
    }

    @Override
    public void onError(final Throwable error) {
      this.delegate.onError(error);
    }

    @Override
    public void onComplete() {
      this.delegate.onComplete();
    }
  }
}
