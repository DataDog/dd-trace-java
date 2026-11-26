package datadog.trace.instrumentation.jaxrs2;

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
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.lang.annotation.Annotation;
import java.lang.annotation.AnnotationFormatError;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JaxRsAnnotationsDecorator extends BaseDecorator {
  private static final Logger log = LoggerFactory.getLogger(JaxRsAnnotationsDecorator.class);

  public static final CharSequence JAX_RS_REQUEST_ABORT =
      UTF8BytesString.create("jax-rs.request.abort");
  public static final CharSequence JAX_RS_CONTROLLER = UTF8BytesString.create("jax-rs-controller");

  public static final String ABORT_FILTER_CLASS =
      "datadog.trace.instrumentation.jaxrs2.filter.abort.class";
  public static final String ABORT_HANDLED =
      "datadog.trace.instrumentation.jaxrs2.filter.abort.handled";
  public static final String ABORT_PARENT =
      "datadog.trace.instrumentation.jaxrs2.filter.abort.parent";
  public static final String ABORT_SPAN = "datadog.trace.instrumentation.jaxrs2.filter.abort.span";

  public static JaxRsAnnotationsDecorator DECORATE = new JaxRsAnnotationsDecorator();

  private static final ClassValue<ConcurrentHashMap<Method, Pair<CharSequence, CharSequence>>>
      RESOURCE_NAMES = GenericClassValue.constructing(ConcurrentHashMap.class);

  /** Filled by the Jersey and RESTEasy AppSec capture advices, keyed by the local root span. */
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
    return JAX_RS_CONTROLLER;
  }

  public void onJaxRsSpan(
      final AgentSpan span, final AgentSpan parent, final Class<?> target, final Method method) {

    final Pair<CharSequence, CharSequence> httpMethodAndRoute =
        getHttpMethodAndRoute(target, method);
    span.setSpanType(InternalSpanTypes.HTTP_SERVER);

    // When jax-rs is the root, we want to name using the path, otherwise use the class/method.
    final boolean isRootScope = parent == null;
    if (isRootScope) {
      if (httpMethodAndRoute.hasRight()) { // no route when no path was readable: keep the name
        markSubResourceLocator(span, httpMethodAndRoute);
        HTTP_RESOURCE_DECORATOR.withRoute(
            span, httpMethodAndRoute.getLeft(), httpMethodAndRoute.getRight());
        tagNormalizedRoute(span, httpMethodAndRoute.getRight());
      }
    } else {
      span.setResourceName(DECORATE.spanNameForMethod(target, method));

      final AgentSpan localRoot = parent.getLocalRootSpan();
      markSubResourceLocator(localRoot, httpMethodAndRoute);
      if (localRoot.getResourceNamePriority() < ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE) {
        parent.setTag(Tags.COMPONENT, "jax-rs");

        // current handler is a filter
        if (!httpMethodAndRoute.hasLeft()
            && (!httpMethodAndRoute.hasRight() || httpMethodAndRoute.getRight().length() == 0)) {
          return;
        }
        // no route when no path was readable: keep the root's name rather than "GET "
        if (!httpMethodAndRoute.hasRight()) {
          return;
        }

        HTTP_RESOURCE_DECORATOR.withRoute(
            localRoot, httpMethodAndRoute.getLeft(), httpMethodAndRoute.getRight());
        tagNormalizedRoute(localRoot, httpMethodAndRoute.getRight());
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
      log.debug(EXCLUDE_TELEMETRY, "Failed to mark a JAX-RS sub-resource locator", t);
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
      log.debug(EXCLUDE_TELEMETRY, "Failed to set the JAX-RS normalized route", t);
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
   * Returns the resource name parts given a JaxRS annotated method. Results are cached so this
   * method can be called multiple times without significantly impacting performance.
   *
   * @return The route can be an empty string. It is {@code null} only when malformed annotations
   *     left no {@code @Path} readable anywhere in the hierarchy, so the span keeps its name.
   */
  @VisibleForTesting
  Pair<CharSequence, CharSequence> getHttpMethodAndRoute(
      final Class<?> target, final Method method) {
    Map<Method, Pair<CharSequence, CharSequence>> classMap = RESOURCE_NAMES.get(target);
    Pair<CharSequence, CharSequence> httpMethodAndRoute = classMap.get(method);
    if (httpMethodAndRoute == null) {
      String httpMethod = null;
      Path methodPath = null;
      final boolean[] pathUnreadable = {false};
      final Path classPath = findClassPath(target, pathUnreadable);
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
            methodPath = findMethodPath(currentMethod, pathUnreadable);
          }

          if (httpMethod != null && methodPath != null) {
            break;
          }
        }
      }
      final CharSequence route =
          classPath == null && methodPath == null && pathUnreadable[0]
              ? null
              : buildRoutePath(classPath, methodPath);
      httpMethodAndRoute = Pair.<CharSequence, CharSequence>of(httpMethod, route);
      classMap.put(method, httpMethodAndRoute);
    }

    return httpMethodAndRoute;
  }

  /*
   * Reading an element's annotations parses all of them, and throws AnnotationFormatError if any is
   * malformed in the application's class path, such as a duplicate of a non-repeatable annotation
   * or an annotation compiled against a different version of its type. Such an element is treated
   * as unannotated, so the rest of the hierarchy is still searched and the result is still cached:
   * the JDK only caches annotations it parsed successfully, so otherwise every request would
   * re-parse them and throw again.
   *
   * A class's annotations are read together with those it inherits, so malformed class-level
   * annotations on a superclass make a subclass's own @Path unreadable too; the route then comes
   * from further up the hierarchy, such as an interface. Each failure is logged at debug.
   */

  private String locateHttpMethod(final Method method) {
    final Annotation[] annotations;
    try {
      annotations = method.getDeclaredAnnotations();
    } catch (AnnotationFormatError e) {
      // unreadable annotations: ignore them, so no http method from this method
      logMalformed(method, e);
      return null;
    }
    for (final Annotation ann : annotations) {
      final Class<? extends Annotation> annotationType = ann.annotationType();
      final HttpMethod annotation;
      try {
        annotation = annotationType.getAnnotation(HttpMethod.class);
      } catch (AnnotationFormatError e) {
        // an unreadable annotation type: ignore it and keep looking for the http method
        logMalformed(annotationType, e);
        continue;
      }
      if (annotation != null) {
        return annotation.value();
      }
    }
    return null;
  }

  /** Sets {@code unreadable[0]} when the method's annotations can't be read. */
  private Path findMethodPath(final Method method, final boolean[] unreadable) {
    try {
      return method.getAnnotation(Path.class);
    } catch (AnnotationFormatError e) {
      // unreadable annotations: ignore them, so no path from this method
      logMalformed(method, e);
      unreadable[0] = true;
      return null;
    }
  }

  /** Sets {@code unreadable[0]} when a class's annotations in the hierarchy can't be read. */
  private Path findClassPath(final Class<?> target, final boolean[] unreadable) {
    for (final Class<?> currentClass : new ClassHierarchyIterable(target)) {
      final Path annotation;
      try {
        annotation = currentClass.getAnnotation(Path.class);
      } catch (AnnotationFormatError e) {
        // unreadable annotations: ignore this class and look further up the hierarchy
        logMalformed(currentClass, e);
        unreadable[0] = true;
        continue;
      }
      if (annotation != null) {
        // Annotation overridden, no need to continue.
        return annotation;
      }
    }

    return null;
  }

  private static void logMalformed(final Object element, final AnnotationFormatError e) {
    log.debug(EXCLUDE_TELEMETRY, "Ignoring unreadable JAX-RS annotations on {}", element, e);
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
