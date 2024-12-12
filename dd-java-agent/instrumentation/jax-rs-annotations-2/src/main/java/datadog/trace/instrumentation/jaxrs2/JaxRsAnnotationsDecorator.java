package datadog.trace.instrumentation.jaxrs2;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.agent.tooling.ClassHierarchyIterable;
import datadog.trace.api.GenericClassValue;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;

public class JaxRsAnnotationsDecorator extends BaseDecorator {

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
      HTTP_RESOURCE_DECORATOR.withRoute(
          span, httpMethodAndRoute.getLeft(), httpMethodAndRoute.getRight());
    } else {
      span.setResourceName(DECORATE.spanNameForMethod(target, method));

      if (parent.getLocalRootSpan().getResourceNamePriority()
          < ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE) {
        parent.setTag(Tags.COMPONENT, "jax-rs");

        // current handler is a filter
        if (!httpMethodAndRoute.hasLeft()
            && (!httpMethodAndRoute.hasRight() || httpMethodAndRoute.getRight().length() == 0)) {
          return;
        }

        HTTP_RESOURCE_DECORATOR.withRoute(
            parent.getLocalRootSpan(), httpMethodAndRoute.getLeft(), httpMethodAndRoute.getRight());
      }
    }
  }

  /**
   * Returns the resource name parts given a JaxRS annotated method. Results are cached so this
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
