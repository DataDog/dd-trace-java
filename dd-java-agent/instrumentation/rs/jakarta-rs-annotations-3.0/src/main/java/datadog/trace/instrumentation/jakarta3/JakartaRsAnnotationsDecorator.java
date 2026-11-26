package datadog.trace.instrumentation.jakarta3;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static datadog.trace.bootstrap.ContextStores.getContextStore;
import static datadog.trace.bootstrap.ContextStores.getContextStoreId;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.api.GenericClassValue;
import datadog.trace.api.Pair;
import datadog.trace.api.appsec.route.JaxRsRequestMatch;
import datadog.trace.api.appsec.route.JaxRsRouteParser;
import datadog.trace.api.appsec.route.NormalizedRouteHelper;
import datadog.trace.api.appsec.route.RouteTemplate;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.ClassHierarchyIterable;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JakartaRsAnnotationsDecorator extends BaseDecorator {
  private static final Logger log = LoggerFactory.getLogger(JakartaRsAnnotationsDecorator.class);

  public static final CharSequence JAKARTA_RS_REQUEST_ABORT =
      UTF8BytesString.create("jakarta-rs.request.abort");
  public static final CharSequence JAKARTA_RS_CONTROLLER =
      UTF8BytesString.create("jakarta-rs-controller");

  public static final String ABORT_FILTER_CLASS =
      "datadog.trace.instrumentation.jakarta3.filter.abort.class";
  public static final String ABORT_HANDLED =
      "datadog.trace.instrumentation.jakarta3.filter.abort.handled";
  public static final String ABORT_PARENT =
      "datadog.trace.instrumentation.jakarta3.filter.abort.parent";
  public static final String ABORT_SPAN =
      "datadog.trace.instrumentation.jakarta3.filter.abort.span";

  public static JakartaRsAnnotationsDecorator DECORATE = new JakartaRsAnnotationsDecorator();

  private static final ClassValue<ConcurrentHashMap<Method, Pair<CharSequence, CharSequence>>>
      RESOURCE_NAMES = GenericClassValue.constructing(ConcurrentHashMap.class);

  private enum RouteState {
    SUBRESOURCE
  }

  private static final ContextStore<Object, Object> ROUTE_STATES =
      getContextStore(getContextStoreId(AgentSpan.class.getName(), RouteState.class.getName()));

  /** Filled by the Jersey AppSec capture advice, keyed by the local root span. */
  private static final ContextStore<Object, Object> REQUEST_MATCHES =
      getContextStore(
          getContextStoreId(AgentSpan.class.getName(), JaxRsRequestMatch.class.getName()));

  @Override
  protected String[] instrumentationNames() {
    return new String[0];
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return JAKARTA_RS_CONTROLLER;
  }

  public void onJakartaRsSpan(
      final AgentSpan span, final AgentSpan parent, final Class<?> target, final Method method) {

    final Pair<CharSequence, CharSequence> httpMethodAndRoute =
        getHttpMethodAndRoute(target, method);
    span.setSpanType(InternalSpanTypes.HTTP_SERVER);

    // When jakarta-rs is the root, we want to name using the path, otherwise use the class/method.
    final boolean isRootScope = parent == null;
    if (isRootScope) {
      markSubResourceLocator(span, httpMethodAndRoute);
      HTTP_RESOURCE_DECORATOR.withRoute(
          span, httpMethodAndRoute.getLeft(), httpMethodAndRoute.getRight());
      tagNormalizedRoute(span, httpMethodAndRoute.getRight());
    } else {
      span.setResourceName(DECORATE.spanNameForMethod(target, method));

      final AgentSpan localRoot = parent.getLocalRootSpan();
      markSubResourceLocator(localRoot, httpMethodAndRoute);
      if (parent == localRoot) {
        parent.setTag(Tags.COMPONENT, "jakarta-rs");
        HTTP_RESOURCE_DECORATOR.withRoute(
            parent, httpMethodAndRoute.getLeft(), httpMethodAndRoute.getRight());
        tagNormalizedRoute(parent, httpMethodAndRoute.getRight());
      } else if (localRoot != null
          && Tags.SPAN_KIND_SERVER.equals(parent.getTag(Tags.SPAN_KIND))
          && "/".equals(localRoot.getTag(Tags.HTTP_ROUTE))
          && httpMethodAndRoute.getRight().length() > 0) {
        if (httpMethodAndRoute.getLeft() == null) {
          // A locator returns before its subresource is dispatched. Its prefix is unavailable
          // when decorating that subresource, so keep the fallback route for this request.
          ROUTE_STATES.put(localRoot, RouteState.SUBRESOURCE);
        } else if (ROUTE_STATES.get(localRoot) == null) {
          // Refine a server handler's catch-all route, but not from a nested resource call.
          HTTP_RESOURCE_DECORATOR.withRoute(
              localRoot, httpMethodAndRoute.getLeft(), httpMethodAndRoute.getRight());
          tagNormalizedRoute(localRoot, httpMethodAndRoute.getRight());
        }
      }
    }
  }

  /**
   * Marks the request of {@code localRoot} as handled by a sub-resource locator (an annotated
   * method with a route but no http method): the route of its sub-resource methods is truncated, so
   * they set no normalized route. Runs before the capture advice, which keeps the mark. Never
   * throws.
   */
  private static void markSubResourceLocator(
      final AgentSpan localRoot, final Pair<CharSequence, CharSequence> httpMethodAndRoute) {
    if (localRoot == null
        || httpMethodAndRoute.hasLeft()
        || !httpMethodAndRoute.hasRight()
        || httpMethodAndRoute.getRight().length() == 0
        || !NormalizedRouteHelper.isEnabled()) {
      return;
    }
    try {
      REQUEST_MATCHES.put(localRoot, JaxRsRequestMatch.SUBRESOURCE);
    } catch (Throwable t) {
      log.debug(EXCLUDE_TELEMETRY, "Failed to mark a Jakarta RS sub-resource locator", t);
    }
  }

  /**
   * Sets {@code _dd.appsec.normalized_route} on {@code span}, the span that just received {@code
   * route} as {@code http.route} (the local root), from the request match a capture advice stored
   * for it. No match (prefix unknown) or a sub-resource locator means no tag. Never throws.
   */
  private static void tagNormalizedRoute(final AgentSpan span, final CharSequence route) {
    if (!NormalizedRouteHelper.isEnabled()) {
      return;
    }
    try {
      tagNormalizedRoute(span, route, (JaxRsRequestMatch) REQUEST_MATCHES.get(span));
    } catch (Throwable t) {
      log.debug(EXCLUDE_TELEMETRY, "Failed to set the Jakarta RS normalized route", t);
    }
  }

  @VisibleForTesting
  static void tagNormalizedRoute(
      final AgentSpan span, final CharSequence route, final JaxRsRequestMatch match) {
    if (match == null || match == JaxRsRequestMatch.SUBRESOURCE || route == null) {
      return;
    }
    final RouteTemplate template = JaxRsRouteParser.cached(route.toString());
    NormalizedRouteHelper.tag(span, template, match.prefix(), match.presenceFor(template));
  }

  /**
   * Returns the resource name parts given a JakartaRS annotated method. Results are cached so this
   * method can be called multiple times without significantly impacting performance.
   *
   * @return The result can be an empty string but will never be {@code null}.
   */
  private Pair<CharSequence, CharSequence> getHttpMethodAndRoute(
      final Class<?> target, final Method method) {
    Map<Method, Pair<CharSequence, CharSequence>> classMap = RESOURCE_NAMES.get(target);
    Pair<CharSequence, CharSequence> httpMethodAndRoute = classMap.get(method);
    if (httpMethodAndRoute == null) {
      String httpMethod = null;
      Path methodPath = null;
      final Path classPath = findClassPath(target);
      for (final Class<?> currentClass : new ClassHierarchyIterable(target)) {
        Method currentMethod;
        if (currentClass.equals(target)) {
          currentMethod = method;
        } else {
          currentMethod = findMatchingMethod(method, currentClass.getDeclaredMethods());
        }

        if (currentMethod != null) {
          if (httpMethod == null) {
            httpMethod = locateHttpMethod(currentMethod);
          }
          if (methodPath == null) {
            methodPath = findMethodPath(currentMethod);
          }

          if (httpMethod != null && methodPath != null) {
            break;
          }
        }
      }
      httpMethodAndRoute =
          Pair.<CharSequence, CharSequence>of(httpMethod, buildRoutePath(classPath, methodPath));
      classMap.put(method, httpMethodAndRoute);
    }

    return httpMethodAndRoute;
  }

  private String locateHttpMethod(final Method method) {
    String httpMethod = null;
    for (final Annotation ann : method.getDeclaredAnnotations()) {
      final HttpMethod annotation = ann.annotationType().getAnnotation(HttpMethod.class);
      if (annotation != null) {
        httpMethod = annotation.value();
        break;
      }
    }
    return httpMethod;
  }

  private Path findMethodPath(final Method method) {
    return method.getAnnotation(Path.class);
  }

  private Path findClassPath(final Class<?> target) {
    for (final Class<?> currentClass : new ClassHierarchyIterable(target)) {
      final Path annotation = currentClass.getAnnotation(Path.class);
      if (annotation != null) {
        // Annotation overridden, no need to continue.
        return annotation;
      }
    }

    return null;
  }

  private Method findMatchingMethod(final Method baseMethod, final Method[] methods) {
    nextMethod:
    for (final Method method : methods) {
      if (!baseMethod.getReturnType().equals(method.getReturnType())) {
        continue;
      }

      if (!baseMethod.getName().equals(method.getName())) {
        continue;
      }

      final Class<?>[] baseParameterTypes = baseMethod.getParameterTypes();
      final Class<?>[] parameterTypes = method.getParameterTypes();
      if (baseParameterTypes.length != parameterTypes.length) {
        continue;
      }
      for (int i = 0; i < baseParameterTypes.length; i++) {
        if (!baseParameterTypes[i].equals(parameterTypes[i])) {
          continue nextMethod;
        }
      }
      return method;
    }
    return null;
  }

  private String buildRoutePath(final Path classPath, final Path methodPath) {
    final StringBuilder route = new StringBuilder();

    boolean skipSlash = false;
    if (classPath != null) {
      if (!classPath.value().startsWith("/")) {
        route.append('/');
      }
      route.append(classPath.value());
      skipSlash = classPath.value().endsWith("/");
    }

    if (methodPath != null) {
      String path = methodPath.value();
      if (skipSlash) {
        if (path.startsWith("/")) {
          path = path.length() == 1 ? "" : path.substring(1);
        }
      } else if (!path.startsWith("/")) {
        route.append('/');
      }
      route.append(path);
    }

    return route.toString().trim();
  }
}
