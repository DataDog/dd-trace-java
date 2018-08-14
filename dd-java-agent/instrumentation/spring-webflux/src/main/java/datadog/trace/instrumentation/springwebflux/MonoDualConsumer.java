package datadog.trace.instrumentation.springwebflux;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import org.reactivestreams.Subscription;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.server.WebFilter;

import java.util.Collections;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.opentracing.log.Fields.ERROR_OBJECT;

public class MonoDualConsumer<U> implements Consumer<Subscription>, BiConsumer<U, Throwable> {

  private Span span = null;
  private Scope scope = null; // TODO: REWORK THIS, see if can store scope instead of span
  private Span parentSpan = null;

  private final boolean startSpanInConsumer;
  private final boolean logErrorInParentSpan;
  private final boolean activateNewSpan;
  private final String operationName;

  public MonoDualConsumer(final String operationName, final boolean startSpanInConsumer, final boolean logErrorInParentSpan, final boolean activateNewSpan, Object ddstate) {
    this.operationName = operationName;
    this.startSpanInConsumer = startSpanInConsumer;
    this.logErrorInParentSpan = logErrorInParentSpan;
    this.activateNewSpan = activateNewSpan;

    if (logErrorInParentSpan) {
      this.parentSpan = GlobalTracer.get().scopeManager().active().span();
    }
  }

  @Override
  public void accept(Subscription subscription) {
    LoggerFactory.getLogger(RouterFunction.class).warn("scopemanger: " + GlobalTracer.get().scopeManager().toString());
    LoggerFactory.getLogger(RouterFunction.class).warn("TYPE SUB: " + subscription.getClass().getName());
    if (startSpanInConsumer) {
      try {
        throw new Exception("A");
      } catch (Exception e) {
        new Throwable().printStackTrace();
      }
      LoggerFactory.getLogger(WebFilter.class).warn("THREAD mdc: " + Thread.currentThread().toString());
      LoggerFactory.getLogger(WebFilter.class).warn("TIME mdc: " + System.currentTimeMillis());
      LoggerFactory.getLogger(WebFilter.class).warn("HERE mdc: " + GlobalTracer.get().activeSpan().toString());

      scope = GlobalTracer.get()
        .buildSpan(operationName)
        .withTag(Tags.COMPONENT.getKey(), "spring-webflux-controller")
        .startActive(false);

      span = scope.span();
//      if (activateNewSpan) {
//        scope = GlobalTracer.get().scopeManager().activate(span, false);
//      }

      if (!activateNewSpan) {
        scope.close();
      }
    }
    
  }

  @Override
  public void accept(U object, Throwable throwable) {
    LoggerFactory.getLogger(RouterFunction.class).warn("has throwabe: " + (throwable != null));
    LoggerFactory.getLogger(RouterFunction.class).warn("has object: " + (object != null));

    if (startSpanInConsumer && span != null) {
      final Span spanToChange = (logErrorInParentSpan && parentSpan != null) ? parentSpan : span;

      if (throwable != null) {
        LoggerFactory.getLogger(RouterFunction.class).warn("throwabe: " + throwable.getMessage());
        spanToChange.log(Collections.singletonMap(ERROR_OBJECT, throwable));
        Tags.ERROR.set(spanToChange, false);
      }
      if (object != null) {
        spanToChange.setTag("function.class", object.getClass().getName());
        LoggerFactory.getLogger(RouterFunction.class).warn("TYPE: " + object.getClass().getName());
      }

      if (logErrorInParentSpan) {
        LoggerFactory.getLogger(RouterFunction.class).warn("PARENT: " + parentSpan.toString());
        parentSpan.setTag(DDTags.RESOURCE_NAME, span.getBaggageItem(DDTags.RESOURCE_NAME));
        //LoggerFactory.getLogger(RouterFunction.class).warn("SPAN RESNAME: " );
        parentSpan.setTag(DDTags.SPAN_TYPE, DDSpanTypes.WEB_SERVLET);
        LoggerFactory.getLogger(RouterFunction.class).warn("PARENT NOW: " + parentSpan.toString());
      }

      span.finish();

      if (activateNewSpan && scope != null) {
        scope.close();
      }


    }
  }
}
