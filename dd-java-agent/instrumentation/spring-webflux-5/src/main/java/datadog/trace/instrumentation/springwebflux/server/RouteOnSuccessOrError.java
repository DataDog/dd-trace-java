package datadog.trace.instrumentation.springwebflux.server;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;

public class RouteOnSuccessOrError implements Consumer<HandlerFunction<?>> {

  private static final Pattern SPECIAL_CHARACTERS_REGEX = Pattern.compile("[\\(\\)&|]");
  private static final Pattern SPACES_REGEX = Pattern.compile("[ \\t]+");
  private static final Pattern ROUTER_FUNCTION_REGEX = Pattern.compile("\\s*->.*$");
  private static final Pattern METHOD_REGEX =
      Pattern.compile("^(GET|HEAD|POST|PUT|DELETE|CONNECT|OPTIONS|TRACE|PATCH) ");
  private static final Function<String, String> PATH_EXTRACTOR =
      arg ->
          METHOD_REGEX
              .matcher(
                  SPACES_REGEX
                      .matcher(SPECIAL_CHARACTERS_REGEX.matcher(arg).replaceAll(""))
                      .replaceAll(" ")
                      .trim())
              .replaceAll("");

  private final RouterFunction routerFunction;
  private final ServerRequest serverRequest;

  private final DDCache<String, String> parsedRouteCache = DDCaches.newFixedSizeCache(16);

  public RouteOnSuccessOrError(
      final RouterFunction routerFunction, final ServerRequest serverRequest) {
    this.routerFunction = routerFunction;
    this.serverRequest = serverRequest;
  }

  private String parsePredicateString() {
    final String routerFunctionString = routerFunction.toString();
    // Router functions containing lambda predicates should not end up in span tags since they are
    // confusing
    if (routerFunctionString.startsWith(
        "org.springframework.web.reactive.function.server.RequestPredicates$$Lambda")) {
      return null;
    } else {
      return ROUTER_FUNCTION_REGEX.matcher(routerFunctionString).replaceFirst("");
    }
  }

  @Nonnull
  private String parseRoute(@Nonnull String routerString) {
    return parsedRouteCache.computeIfAbsent(routerString, PATH_EXTRACTOR);
  }

  @Override
  public void accept(HandlerFunction<?> handlerFunction) {
    if (handlerFunction == null) {
      // in this case the route is added by instrumenting the method annotation. we stop here.
      return;
    }
    final String predicateString = parsePredicateString();
    if (predicateString != null) {
      final AgentSpan span = (AgentSpan) serverRequest.attributes().get(AdviceUtils.SPAN_ATTRIBUTE);
      if (span != null) {
        span.setTag("request.predicate", predicateString);
      }
      final AgentSpan parentSpan =
          (AgentSpan) serverRequest.attributes().get(AdviceUtils.PARENT_SPAN_ATTRIBUTE);
      if (parentSpan != null) {
        HTTP_RESOURCE_DECORATOR.withRoute(
            parentSpan, serverRequest.methodName(), parseRoute(predicateString));
      }
    }
  }
}
