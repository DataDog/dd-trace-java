package datadog.trace.instrumentation.jaxrs1;

import static datadog.trace.bootstrap.WeakMap.Provider.newWeakMap;

import datadog.trace.agent.decorator.BaseDecorator;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.WeakMap;
import datadog.trace.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.api.Tags;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;

public class JaxRsAnnotationsDecorator extends BaseDecorator {
  public static JaxRsAnnotationsDecorator DECORATE = new JaxRsAnnotationsDecorator();

  private final WeakMap<Class, Map<Method, String>> resourceNames = newWeakMap();

  @Override
  protected String[] instrumentationNames() {
    return new String[0];
  }

  @Override
  protected String spanType() {
    return null;
  }

  @Override
  protected String component() {
    return "jax-rs-controller";
  }

  public void onControllerStart(
      final AgentSpan span, final AgentSpan parent, final Class target, final Method method) {
    final String resourceName = getPathResourceName(target, method);
    updateParent(parent, resourceName);

    span.setTag(DDTags.SPAN_TYPE, DDSpanTypes.HTTP_SERVER);

    // When jax-rs is the root, we want to name using the path, otherwise use the class/method.
    final boolean isRootScope = parent == null;
    if (isRootScope && !resourceName.isEmpty()) {
      span.setTag(DDTags.RESOURCE_NAME, resourceName);
    } else {
      span.setTag(DDTags.RESOURCE_NAME, DECORATE.spanNameForClass(target) + "." + method.getName());
    }
  }

  private void updateParent(AgentSpan span, final String resourceName) {
    if (span == null) {
      return;
    }
    span = span.getLocalRootSpan();
    span.setTag(Tags.COMPONENT, "jax-rs");

    if (!resourceName.isEmpty()) {
      span.setTag(DDTags.RESOURCE_NAME, resourceName);
    }
  }

  /**
   * Returns the resource name given a JaxRS annotated method. Results are cached so this method can
   * be called multiple times without significantly impacting performance.
   *
   * @return The result can be an empty string but will never be {@code null}.
   */
  private String getPathResourceName(final Class target, final Method method) {
    Map<Method, String> classMap = resourceNames.get(target);

    if (classMap == null) {
      resourceNames.putIfAbsent(target, new ConcurrentHashMap<Method, String>());
      classMap = resourceNames.get(target);
      // classMap should not be null at this point because we have a
      // strong reference to target and don't manually clear the map.
    }

    String resourceName = classMap.get(method);
    if (resourceName == null) {
      final String httpMethod = locateHttpMethod(method);
      final List<Path> paths = gatherPaths(target, method);
      resourceName = buildResourceName(httpMethod, paths);
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

  private List<Path> gatherPaths(Class<Object> target, final Method method) {
    final List<Path> paths = new ArrayList();
    while (target != null && target != Object.class) {
      final Path annotation = target.getAnnotation(Path.class);
      if (annotation != null) {
        paths.add(annotation);
        break; // Annotation overridden, no need to continue.
      }
      target = target.getSuperclass();
    }
    final Path methodPath = method.getAnnotation(Path.class);
    if (methodPath != null) {
      paths.add(methodPath);
    }
    return paths;
  }

  private String buildResourceName(final String httpMethod, final List<Path> paths) {
    final String resourceName;
    final StringBuilder resourceNameBuilder = new StringBuilder();
    if (httpMethod != null) {
      resourceNameBuilder.append(httpMethod);
      resourceNameBuilder.append(" ");
    }
    Path last = null;
    for (final Path path : paths) {
      if (path.value().startsWith("/") || (last != null && last.value().endsWith("/"))) {
        resourceNameBuilder.append(path.value());
      } else {
        resourceNameBuilder.append("/");
        resourceNameBuilder.append(path.value());
      }
      last = path;
    }
    resourceName = resourceNameBuilder.toString().trim();
    return resourceName;
  }
}
