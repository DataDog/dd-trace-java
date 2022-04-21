package datadog.trace.instrumentation.jaxrs1;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.agent.tooling.ClassHierarchyIterable;
import datadog.trace.api.GenericClassValue;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.PathSegment;

public class JaxRsAnnotationsDecorator extends BaseDecorator {
  public static JaxRsAnnotationsDecorator DECORATE = new JaxRsAnnotationsDecorator();

  private static final ClassValue<ConcurrentHashMap<Method, MethodDetails>> RESOURCE_NAMES =
      GenericClassValue.constructing(ConcurrentHashMap.class);

  public static final CharSequence JAX_RS_CONTROLLER = UTF8BytesString.create("jax-rs-controller");

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
      final AgentSpan span,
      final AgentSpan parent,
      final Class<?> target,
      final Method method,
      Object[] arguments) {

    final MethodDetails methodDetails = getMethodDetails(target, method);
    span.setSpanType(InternalSpanTypes.HTTP_SERVER);

    // When jax-rs is the root, we want to name using the path, otherwise use the class/method.
    final boolean isRootScope = parent == null;
    if (isRootScope) {
      HTTP_RESOURCE_DECORATOR.withRoute(span, methodDetails.method, methodDetails.route);
    } else {
      // This check ensures that we only use the route from the first JAX-RS annotated method that
      // is executed
      if (parent.getLocalRootSpan().getResourceNamePriority()
          < ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE) {
        HTTP_RESOURCE_DECORATOR.withRoute(
            parent.getLocalRootSpan(), methodDetails.method, methodDetails.route);
        parent.getLocalRootSpan().setTag(Tags.COMPONENT, "jax-rs");
      }

      span.setResourceName(DECORATE.spanNameForMethod(target, method));
    }

    maybePublishPathParameters(span, arguments, methodDetails);
  }

  private void maybePublishPathParameters(
      AgentSpan span, Object[] arguments, MethodDetails methodDetails) {
    if (methodDetails.pathParameters.isEmpty()) {
      return;
    }

    CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
    BiFunction<RequestContext<Object>, Map<String, ?>, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestPathParams());
    RequestContext<Object> requestContext = span.getRequestContext();
    if (requestContext == null || callback == null) {
      return;
    }

    Map<String, ?> pathParams = buildPathParams(methodDetails.pathParameters, arguments);
    callback.apply(requestContext, pathParams);
  }

  private Map<String, ?> buildPathParams(Map<String, Integer> pathParamSpec, Object[] arguments) {
    Map<String, Object> pathParams = new HashMap<>();
    for (Map.Entry<String, Integer> e : pathParamSpec.entrySet()) {
      Object newValue = convertValue(arguments[e.getValue()]);
      pathParams.put(e.getKey(), newValue);
    }
    return pathParams;
  }

  private Object convertValue(Object orig) {
    if (orig instanceof String) {
      return orig;
    }
    if (orig instanceof List) {
      List<Object> newList = new ArrayList<>(((List<?>) orig).size());
      for (Object o : (List<?>) orig) {
        newList.add(convertValue(o));
      }
      return newList;
    }
    if (orig instanceof PathSegment) {
      PathSegment seg = (PathSegment) orig;
      String path = seg.getPath();
      MultivaluedMap<String, String> matrixParameters = seg.getMatrixParameters();
      if (matrixParameters == null || matrixParameters.isEmpty()) {
        return path;
      }
      if (path == null || path.isEmpty()) {
        return matrixParameters;
      }
      Map<String, Object> ret = new HashMap<>();
      ret.put("path", path);
      ret.put("matrixParameters", matrixParameters);
    }
    return orig.toString();
  }

  /**
   * Returns the resource name parts given a JaxRS annotated method. Results are cached so this
   * method can be called multiple times without significantly impacting performance.
   *
   * @return The result can be an empty string but will never be {@code null}.
   */
  private MethodDetails getMethodDetails(final Class<?> target, final Method method) {
    ConcurrentHashMap<Method, MethodDetails> classMap = RESOURCE_NAMES.get(target);
    MethodDetails methodDetails = classMap.get(method);
    if (methodDetails == null) {
      String httpMethod = null;
      Path methodPath = null;
      final Path classPath = findClassPath(target);
      for (final Class<?> currentClass : new ClassHierarchyIterable(target)) {
        final Method currentMethod;
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
      String route = buildRoutePath(classPath, methodPath);
      methodDetails = new MethodDetails(httpMethod, route, findPathParameters(method));
      classMap.put(method, methodDetails);
    }

    return methodDetails;
  }

  private static Map<String, Integer> findPathParameters(Method method) {
    Map<String, Integer> result = Collections.emptyMap();

    Annotation[][] parameterAnnotations = method.getParameterAnnotations();
    for (int i = 0; i < parameterAnnotations.length; i++) {
      Annotation anns[] = parameterAnnotations[i];
      for (Annotation a : anns) {
        if (!PathParam.class.isAssignableFrom(a.getClass())) {
          continue;
        }
        if (result.isEmpty()) {
          result = new HashMap<>();
        }

        String name = ((PathParam) a).value();
        result.put(name, i);
      }
    }
    return result;
  }

  private String locateHttpMethod(final Method method) {
    String httpMethod = null;
    for (final Annotation ann : method.getDeclaredAnnotations()) {
      if (ann.annotationType().getAnnotation(HttpMethod.class) != null) {
        httpMethod = ann.annotationType().getSimpleName();
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
        route.append("/");
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
        route.append("/");
      }
      route.append(path);
    }

    return route.toString().trim();
  }

  public static class MethodDetails {
    private final CharSequence method;
    private final CharSequence route;
    private final Map<String, Integer> pathParameters;

    public MethodDetails(
        CharSequence method, CharSequence route, Map<String, Integer> pathParameters) {
      this.method = method;
      this.route = route;
      this.pathParameters = pathParameters;
    }
  }
}
