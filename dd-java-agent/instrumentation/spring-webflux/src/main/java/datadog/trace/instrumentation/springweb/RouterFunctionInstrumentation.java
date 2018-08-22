package datadog.trace.instrumentation.springweb;

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
      //not(isInterface()).and(
      not(isAbstract()).and(ElementMatchers.<TypeDescription>declaresField(ElementMatchers.<FieldDescription>named("predicate")))
        .and(safeHasSuperType(named("org.springframework.web.reactive.function.server.RouterFunctions$AbstractRouterFunction")));

    //return named("org.springframework.web.reactive.function.server.RouterFunctions$DefaultRouterFunction");
//      .and(nameStartsWith("org.springframework.web.reactive.HandlerAdapter"))
//      .and(nameEndsWith("org.springframework.web.reactive.HandlerAdapter"));
  }

//  @Override
//  public ElementMatcher<ClassLoader> classLoaderMatcher() {
//    return classLoaderHasClassWithField(
//        "org.springframework.web.reactive.HandlerMapping", "BEST_MATCHING_PATTERN_ATTRIBUTE");
//  }

//  @Override
//  public Map<ElementMatcher, String> transformers() {
//    return Collections.<ElementMatcher, String>singletonMap(
//        isMethod()
//            .and(isPublic())
//            .and(nameStartsWith("handle"))
//            .and(takesArgument(0, named("org.springframework.web.server.ServerWebExchange")))
//            .and(takesArguments(2)
//            ),
//        HandlerFunctionAdvice.class.getName());
//  }
//
//  public static class HandlerFunctionAdvice {
//
//    @Advice.OnMethodEnter(suppress = Throwable.class)
//    public static Scope nameResourceAndStartSpan(
//        @Advice.Argument(0) final ServerWebExchange exchange,
//        @Advice.Argument(1) final Object handler) {
//      // Name the parent span based on the matching pattern
//      // This is likely the servlet.request span.
//      final Scope parentScope = GlobalTracer.get().scopeManager().active();
//      if (parentScope != null && exchange != null) {
//        final HttpRequest request = exchange.getRequest();
//        final String method = request.getMethodValue();
//
//        Map<String, Object> attrs = exchange.getAttributes();
//
//        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
//          LoggerFactory.getLogger(HandlerAdapter.class).error(entry.getKey() + ":" + entry.getValue() + ", type: " + entry.getValue().getClass().getSimpleName());
//        }
//
//        Object uriTemplateVars =
//            exchange.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
//        if (uriTemplateVars == null) {
//          uriTemplateVars = exchange.getAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
//        }
//
//        if (uriTemplateVars != null) {
//          LoggerFactory.getLogger(HandlerAdapter.class).error(uriTemplateVars.toString());
//        } else {
//          LoggerFactory.getLogger(HandlerAdapter.class).error("null pattern");
//        }
//
//        String pattern = "";
//        // uri template var is in form of {var=value} map type
//        if (bestMatchingPattern instanceof String) {
//
//          if (bestMatchingPattern instanceof Map) {
//            for (Map.Entry<String, String> entry : ((Map<String, String>) bestMatchingPattern).entrySet()) {
//              LoggerFactory.getLogger(HandlerAdapter.class).error(entry.getKey() + ":" + entry.getValue() + ", type: " + entry.getValue().getClass().getSimpleName());
//            }
//            pattern = ((Map.Entry) bestMatchingPattern).getKey().toString();
//
//          }
////          pattern = bestMatchingPattern.toString();
////          String reqPath = exchange.getRequest().getPath().value();
////          int equalSignIdx = pattern.indexOf("=");
////          pattern = pattern.substring(0, equalSignIdx) + "}";
//        }
//        LoggerFactory.getLogger(HandlerAdapter.class).error("pattern: " + pattern);
//        if (method != null) {
//          final String resourceName = method + " " + pattern;
//          parentScope.span().setTag(DDTags.RESOURCE_NAME, resourceName);
//          parentScope.span().setTag(DDTags.SPAN_TYPE, DDSpanTypes.WEB_SERVLET);
//        }
//      }
//
//      // Now create a span for controller execution.
//
//      final Class<?> clazz;
//      final String methodName;
//
//      if (handler instanceof HandlerMethod) {
//        // name span based on the class and method name defined in the handler
//        final Method method = ((HandlerMethod) handler).getMethod();
//        clazz = method.getDeclaringClass();
//        methodName = method.getName();
//      } else if (handler instanceof HttpRequestHandler) {
//        // org.springframework.web.servlet.mvc.HttpRequestHandlerAdapter
//        clazz = handler.getClass();
//        methodName = "handleRequest";
//      } else if (handler instanceof HandlerFunction) {
//        clazz = handler.getClass();
//        methodName = "handle";
//      } else {
//        // perhaps org.springframework.web.servlet.mvc.annotation.AnnotationMethodHandlerAdapter
//        clazz = handler.getClass();
//        methodName = "<annotation>";
//      }
//
//      String className = clazz.getSimpleName();
//      if (className.isEmpty()) {
//        className = clazz.getName();
//        if (clazz.getPackage() != null) {
//          final String pkgName = clazz.getPackage().getName();
//          if (!pkgName.isEmpty()) {
//            className = clazz.getName().replace(pkgName, "").substring(1);
//          }
//        }
//      }
//
//      final String operationName = className + "." + methodName;
//
//      return GlobalTracer.get()
//          .buildSpan(operationName)
//          .withTag(Tags.COMPONENT.getKey(), "spring-webflux-controller")
//          .startActive(true);
//    }

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
          parentScope.span().setTag(DDTags.RESOURCE_NAME, resourceName);
          parentScope.span().setTag(DDTags.SPAN_TYPE, DDSpanTypes.WEB_SERVLET);
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
      @Advice.Enter final Scope scope, @Advice.Thrown final Throwable throwable, @Advice.Return(readOnly = false) Mono returnMono) {

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
        final MonoDualConsumer dualConsumer = new MonoDualConsumer();
        returnMono = returnMono.doOnSubscribe(dualConsumer);
        returnMono = returnMono.doOnSuccessOrError(dualConsumer);
      }


    }
  }

  

}
