package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty12.JettyDecorator.DECORATE;

import datadog.appsec.api.blocking.BlockingException;
import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.Function;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

/**
 * Finishes the server span when the Jetty 12 stream completes with a failure.
 *
 * <p>On success the stream recycles the {@code HttpChannelState} and {@code
 * JettyServerAdvice.ResetAdvice} finishes the span. A failed stream (for example a request aborted
 * after its response was committed, as ee10 does when a {@link BlockingException} propagates after
 * a blocking response was written) closes the connection without recycling the channel, so the span
 * would never be finished. The context attribute is removed before finishing, so the span is
 * finished at most once.
 */
public final class JettyStreamFailureWrapper extends HttpStream.Wrapper {
  private static final int MAX_CAUSE_DEPTH = 16;

  private final Request request;
  private final Response response;

  JettyStreamFailureWrapper(
      final HttpStream wrapped, final Request request, final Response response) {
    super(wrapped);
    this.request = request;
    this.response = response;
  }

  /** Wraps the current stream of {@code request}; a no-op when there is no active stream. */
  public static void install(final Request request, final Response response) {
    try {
      request.addHttpStreamWrapper(new Installer(request, response));
    } catch (final RuntimeException ignored) {
      // no active stream: nothing to wrap
    }
  }

  @Override
  public void failed(final Throwable failure) {
    try {
      finishSpan(request, response, failure);
    } catch (final Throwable ignored) {
      // never prevent the stream from completing
    }
    super.failed(failure);
  }

  static void finishSpan(final Request request, final Response response, final Throwable failure) {
    final Object contextObj = request.removeAttribute(DD_CONTEXT_ATTRIBUTE);
    if (!(contextObj instanceof Context)) {
      return;
    }
    final Context context = (Context) contextObj;
    final AgentSpan span = fromContext(context);
    if (span == null) {
      return;
    }
    final Throwable blockingException = findBlockingException(failure);
    if (blockingException != null) {
      DECORATE.onError(span, blockingException);
    }
    DECORATE.onResponse(span, request, response);
    DECORATE.beforeFinish(context);
    span.finish();
  }

  /** Looks for a {@link BlockingException} in the cause chain and the top-level suppressed list. */
  static Throwable findBlockingException(final Throwable failure) {
    if (failure == null) {
      return null;
    }
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (current instanceof BlockingException) {
        return current;
      }
      current = current.getCause();
    }
    for (final Throwable suppressed : failure.getSuppressed()) {
      if (suppressed instanceof BlockingException) {
        return suppressed;
      }
    }
    return null;
  }

  /** Stream wrapper factory passed to {@link Request#addHttpStreamWrapper}. */
  static final class Installer implements Function<HttpStream, HttpStream> {
    private final Request request;
    private final Response response;

    Installer(final Request request, final Response response) {
      this.request = request;
      this.response = response;
    }

    @Override
    public HttpStream apply(final HttpStream stream) {
      return new JettyStreamFailureWrapper(stream, request, response);
    }
  }
}
