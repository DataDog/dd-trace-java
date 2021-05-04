package datadog.trace.instrumentation.jaxrs1;

import datadog.trace.agent.tooling.ClassHierarchyIterable;
import datadog.trace.api.GenericClassValue;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
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
  public static JaxRsAnnotationsDecorator DECORATE = new JaxRsAnnotationsDecorator();

  private static final ClassValue<ConcurrentHashMap<Method, String>> RESOURCE_NAMES =
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
      final AgentSpan span, final AgentSpan parent, final Class<?> target, final Method method) {

    final String resourceName = getPathResourceName(target, method);
    updateRootSpan(parent, resourceName);

    span.setSpanType(InternalSpanTypes.HTTP_SERVER);

    // When jax-rs is the root, we want to name using the path, otherwise use the class/method.
    final boolean isRootScope = parent == null;
    if (isRootScope && !resourceName.isEmpty()) {
      span.setResourceName(resourceName);
    } else {
      span.setResourceName(DECORATE.spanNameForMethod(target, method));
    }
  }

  private void updateRootSpan(AgentSpan span, final String resourceName) {
    if (span == null) {
      return;
    }
    span = span.getLocalRootSpan();

    if (!span.hasResourceName()) {
      span.setTag(Tags.COMPONENT, "jax-rs");

      if (!resourceName.isEmpty()) {
        span.setResourceName(resourceName);
      }
    }
  }

  /**
   * Returns the resource name given a JaxRS annotated method. Results are cached so this method can
   * be called multiple times without significantly impacting performance.
   *
   * @return The result can be an empty string but will never be {@code null}.
   */
  private String getPathResourceName(final Class<?> target, final Method method) {
    Map<Method, String> classMap = RESOURCE_NAMES.get(target);
    String resourceName = classMap.get(method);
    if (resourceName == null) {
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
      resourceName = buildResourceName(httpMethod, classPath, methodPath);
      classMap.put(method, resourceName);
    }

    return resourceName;
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

  private String buildResourceName(
      final String httpMethod, final Path classPath, final Path methodPath) {
    final String resourceName;
    final StringBuilder resourceNameBuilder = new StringBuilder();
    if (httpMethod != null) {
      resourceNameBuilder.append(httpMethod);
      resourceNameBuilder.append(" ");
    }
    boolean skipSlash = false;
    if (classPath != null) {
      if (!classPath.value().startsWith("/")) {
        resourceNameBuilder.append("/");
      }
      resourceNameBuilder.append(classPath.value());
      skipSlash = classPath.value().endsWith("/");
    }

    if (methodPath != null) {
      String path = methodPath.value();
      if (skipSlash) {
        if (path.startsWith("/")) {
          path = path.length() == 1 ? "" : path.substring(1);
        }
      } else if (!path.startsWith("/")) {
        resourceNameBuilder.append("/");
      }
      resourceNameBuilder.append(path);
    }

    resourceName = resourceNameBuilder.toString().trim();
    return resourceName;
  }
}
