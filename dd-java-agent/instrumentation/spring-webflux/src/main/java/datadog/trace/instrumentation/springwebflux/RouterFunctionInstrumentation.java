package datadog.trace.instrumentation.springwebflux;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;

import java.util.Collections;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.reactivestreams.Subscription;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.HandlerAdapter;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Mono;

@AutoService(Instrumenter.class)
public final class RouterFunctionInstrumentation extends Instrumenter.Default {

  public RouterFunctionInstrumentation() {
    super("spring-webflux");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
      RouterFunctionInstrumentation.class.getPackage().getName() + ".MonoDualConsumer"
    };
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return
      not(isAbstract()).and(declaresField(named("predicate")))
        .and(safeHasSuperType(named("org.springframework.web.reactive.function.server.RouterFunctions$AbstractRouterFunction")));
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    return Collections.<ElementMatcher, String>singletonMap(
      isMethod()
        .and(isPublic())
        .and(nameStartsWith("route"))
        .and(takesArgument(0, named("org.springframework.web.reactive.function.server.ServerRequest")))
        .and(takesArguments(1)
        ),
      RouterFunctionAdvice.class.getName());
  }

  public static class RouterFunctionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope nameResourceAndStartSpan(
      @Advice.FieldValue(value = "predicate") final RequestPredicate predicate,
      @Advice.This final RouterFunction routerFunction,
      @Advice.Argument(0) final ServerRequest serverRequest) {

//      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(RouterFunction.class);
//      if (callDepth > 0) {
//        return null;
//      }

      // Name the parent span based on the matching predicateString
      // This is likely the servlet.request span.
      final Scope parentScope = GlobalTracer.get().scopeManager().active();

      if (predicate == null) {
        return null;
      }

      final String predicateString = predicate.toString();
      LoggerFactory.getLogger(HandlerAdapter.class).error("predicate: " + predicate.getClass().getName());
      LoggerFactory.getLogger(HandlerAdapter.class).error("predicate declared in: " + predicate.getClass().getEnclosingClass());
      if (parentScope != null && predicate.test(serverRequest) && serverRequest != null) {
        // only change parent span if the predicate is one of those enclosed in org.springframework.web.reactive.function.server RequestPredicates
        // otherwise the parent may have weird resource names such as lambda request predicate class names that arise
        // from webflux error handling
        final Class predicateEnclosingClass = predicate.getClass().getEnclosingClass();
        LoggerFactory.getLogger(HandlerAdapter.class).error("predicate class eql: " + (predicateEnclosingClass == RequestPredicates.class));
        if (predicateString != null && !predicateString.isEmpty() && predicateEnclosingClass == RequestPredicates.class) {
          final String resourceName = predicateString.replace("&&", "")
            .replace("||", "").replace("(", "").replace(")", "");

          final Span parentSpan = parentScope.span();
          parentScope.close();
          final Scope parentParentScope = GlobalTracer.get().scopeManager().active();

          if (parentParentScope == null) {
            GlobalTracer.get().scopeManager().activate(parentSpan, false);
            parentSpan.setTag(DDTags.RESOURCE_NAME, resourceName);
            parentSpan.setTag(DDTags.SPAN_TYPE, DDSpanTypes.WEB_SERVLET);
          } else {
            parentParentScope.span().setTag(DDTags.RESOURCE_NAME, resourceName);
            parentParentScope.span().setTag(DDTags.SPAN_TYPE, DDSpanTypes.WEB_SERVLET);
            GlobalTracer.get().scopeManager().activate(parentSpan, false);
          }
        }

        final String operationName = routerFunction.getClass().getSimpleName() + ".route";

        return GlobalTracer.get()
          .buildSpan(operationName)
          .withTag(Tags.COMPONENT.getKey(), "spring-webflux-controller")
          .withTag("predicateString", predicateString)
          .startActive(true);
      } else {
        return null;
      }

    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
      @Advice.Enter final Scope scope, @Advice.Thrown final Throwable throwable, @Advice.Return(readOnly = false) Mono<HandlerFunction<?>> returnMono) {

      if (scope != null) {
        final Span span = scope.span();
        if (throwable != null) {

          Tags.ERROR.set(span, true);
          span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
        }
        scope.close();

        // if a predicate test returns false, a route span would not have been started
        // final String handleOperationName = returnMono.getClass().getSimpleName() + ".handle";
//        final String handleOperationName = ((ParameterizedType) returnMono.getClass()
//          .getGenericSuperclass()).getActualTypeArguments()[0].toString() + ".handle";
        final MonoDualConsumer dualConsumer = new MonoDualConsumer("Mono.HandlerFunction", true, false, false);
        returnMono = returnMono.doOnSubscribe(dualConsumer);
        returnMono = returnMono.doOnSuccessOrError(dualConsumer);
      }


    }
  }

  

}
