package datadog.trace.instrumentation.springwebflux.server;

import static datadog.trace.instrumentation.springwebflux.server.SpringWebfluxHttpServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

@Slf4j
public class AdviceUtils {

  public static final String PUBLISHER_CONTEXT_KEY =
      "datadog.trace.instrumentation.reactor.core.Span";
  public static final String SPAN_ATTRIBUTE = "datadog.trace.instrumentation.springwebflux.Span";
  public static final String PARENT_SPAN_ATTRIBUTE =
      "datadog.trace.instrumentation.springwebflux.ParentSpan";

  public static String parseOperationName(final Object handler) {
    final String className = DECORATE.spanNameForClass(handler.getClass());
    final String operationName;
    final int lambdaIdx = className.indexOf("$$Lambda$");

    if (lambdaIdx > -1) {
      operationName = className.substring(0, lambdaIdx) + ".lambda";
    } else {
      operationName = className + ".handle";
    }
    return operationName;
  }

  public static <T> Mono<T> setPublisherSpan(final Mono<T> mono, final AgentSpan span) {
    return mono.<T>transform(finishSpanNextOrError())
        .subscriberContext(Context.of(PUBLISHER_CONTEXT_KEY, span));
  }

  public static <T> Flux<T> setPublisherSpan(final Flux<T> flux, final AgentSpan span) {
    return flux.<T>transform(finishSpanNextOrError())
        .subscriberContext(Context.of(PUBLISHER_CONTEXT_KEY, span));
  }

  /**
   * Idea for this has been lifted from https://github.com/reactor/reactor-core/issues/947. Newer
   * versions of reactor-core have easier way to access context but we want to support older
   * versions.
   */
  public static <T, IP>
      Function<? super Publisher<T>, ? extends Publisher<T>> finishSpanNextOrError() {
    return Operators.lift((scannable, subscriber) -> new SpanFinishingSubscriber<>(subscriber));
  }

  public static void finishSpanIfPresent(
      final ServerWebExchange exchange, final Throwable throwable) {
    if (exchange != null) {
      finishSpanIfPresentInAttributes(exchange.getAttributes(), throwable);
    }
  }

  public static void finishSpanIfPresent(
      final ServerRequest serverRequest, final Throwable throwable) {
    if (serverRequest != null) {
      finishSpanIfPresentInAttributes(serverRequest.attributes(), throwable);
    }
  }

  public static void finishSpanIfPresent(
      final ClientRequest clientRequest, final Throwable throwable) {
    if (clientRequest != null) {
      finishSpanIfPresentInAttributes(clientRequest.attributes(), throwable);
    }
  }

  private static void finishSpanIfPresentInAttributes(
      final Map<String, Object> attributes, final Throwable throwable) {

    final AgentSpan span = (AgentSpan) attributes.remove(SPAN_ATTRIBUTE);
    finishSpanIfPresent(span, throwable);
  }

  static void finishSpanIfPresent(final AgentSpan span, final Throwable throwable) {
    if (span != null) {
      if (throwable != null) {
        span.setError(true);
        span.addThrowable(throwable);
      }
      span.finish();
    }
  }

  public static class SpanFinishingSubscriber<T> implements CoreSubscriber<T> {

    private final Context context;
    private final CoreSubscriber<? super T> subscriber;

    public SpanFinishingSubscriber(final CoreSubscriber<? super T> subscriber) {
      this.subscriber = subscriber;
      context = subscriber.currentContext();
    }

    @Override
    public void onSubscribe(final Subscription s) {
      subscriber.onSubscribe(s);
    }

    @Override
    public void onNext(final T t) {
      subscriber.onNext(t);
    }

    @Override
    public void onError(final Throwable t) {
      finishSpanIfPresent(context.getOrDefault(PUBLISHER_CONTEXT_KEY, (AgentSpan) null), t);
      subscriber.onError(t);
    }

    @Override
    public void onComplete() {
      finishSpanIfPresent(context.getOrDefault(PUBLISHER_CONTEXT_KEY, (AgentSpan) null), null);
      subscriber.onComplete();
    }

    @Override
    public Context currentContext() {
      return context;
    }
  }
}
