package datadog.trace.instrumentation.springweb;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import org.reactivestreams.Subscription;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.server.RouterFunction;

import java.util.Collections;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.opentracing.log.Fields.ERROR_OBJECT;

public class MonoDualConsumer<U> implements Consumer<Subscription>, BiConsumer<U, Throwable> {

  private Span span = null;
  // private final String operationName;
//    final WebfluxHandlerFunctionSpanStartConsumer onSubscribeConsumer;
//    final WebfluxHandlerFunctionSpanFinishConsumer spanFinishConsumer;

  public MonoDualConsumer(/*final String operationName*/) {
//      this.spanFinishConsumer = new WebfluxHandlerFunctionSpanFinishConsumer(this);
//      this.onSubscribeConsumer = new WebfluxHandlerFunctionSpanStartConsumer(operationName, this);
    //this.operationName = operationName;
  }

  protected Span getSpan() {
    return span;
  }

  protected void setSpan(Span span) {
    this.span = span;
  }

//    static class WebfluxHandlerFunctionSpanFinishConsumer implements Consumer<?> {
//
//      private final WebfluxHandlerFunctionMonoConsumerManager manager;
//
//      private WebfluxHandlerFunctionSpanFinishConsumer(final WebfluxHandlerFunctionMonoConsumerManager manager) {
//        this.manager = manager;
//      }
//
//      @Override
//      public void accept(Object object) {
//        Span span = manager.getSpan();
//        if (object instanceof Throwable) {
//          Tags.ERROR.set(span, true);
//          span.log(Collections.singletonMap(ERROR_OBJECT, (Throwable) object));
//        }
//        LoggerFactory.getLogger(HandlerFunction.class).warn("TYPE: " + object.getClass().getName());
//        span.finish();
//      }
//    }
//
//    static class WebfluxHandlerFunctionSpanStartConsumer implements Consumer<Subscription> {
//
//      private final String operationName;
//      private final WebfluxHandlerFunctionMonoConsumerManager manager;
//
//      public WebfluxHandlerFunctionSpanStartConsumer(final String operationName, final WebfluxHandlerFunctionMonoConsumerManager manager) {
//        this.operationName = operationName;
//        this.manager = manager;
//      }

  @Override
  public void accept(Subscription subscription) {
    LoggerFactory.getLogger(RouterFunction.class).warn("TYPE SUB: " + subscription.getClass().getName());
    final Scope scope = GlobalTracer.get()
      .buildSpan("HandlerFunctionMono")
      .withTag(Tags.COMPONENT.getKey(), "spring-webflux-controller")
      .startActive(false);

    span = scope.span();

    scope.close();
  }

  @Override
  public void accept(U object, Throwable throwable) {
    LoggerFactory.getLogger(RouterFunction.class).warn("has throwabe: " + (throwable != null));
    LoggerFactory.getLogger(RouterFunction.class).warn("has object: " + (object != null));
    if (span != null) {

      if (throwable != null) {
        LoggerFactory.getLogger(RouterFunction.class).warn("throwabe: " + throwable.getMessage());
        Tags.ERROR.set(span, true);
        span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
      } else {
        span.setTag("object.type", object.getClass().getName());
        LoggerFactory.getLogger(RouterFunction.class).warn("TYPE: " + object.getClass().getName());
      }

      span.finish();
    }

  }
  // }
}
