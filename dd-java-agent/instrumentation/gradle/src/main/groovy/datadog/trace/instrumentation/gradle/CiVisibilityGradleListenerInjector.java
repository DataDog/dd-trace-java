package datadog.trace.instrumentation.gradle;

import org.gradle.initialization.ClassLoaderRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.BuildScopeServices;

public class CiVisibilityGradleListenerInjector {

  public static void inject(ServiceRegistry parentServices, BuildScopeServices buildScopeServices) {
    ClassLoaderRegistry classLoaderRegistry = parentServices.get(ClassLoaderRegistry.class);
    Class<?> ciVisibilityGradleListener = loadCiVisibilityGradleListener(classLoaderRegistry);
    buildScopeServices.register(
        serviceRegistration -> serviceRegistration.add(ciVisibilityGradleListener));
  }

  /**
   * There are several class loaders in Gradle that load various parts of the system: core classes,
   * plugins, etc. This provider class is injected into the core classloader since it is invoked by
   * the build service registry, which is a part of the Gradle core. The CI Visibility listener
   * (that is being registered as a service here) is injected into the plugins classloader because
   * it needs to work with domain objects from Gradle Testing JVM (e.g. {@link
   * org.gradle.api.tasks.testing.Test} task), which is a plugin. Therefore, we cannot reference its
   * {@code Class} instance directly, and instead have to load it explicitly.
   */
  private static Class<?> loadCiVisibilityGradleListener(ClassLoaderRegistry classLoaderRegistry) {
    try {
      return classLoaderRegistry
          .getPluginsClassLoader()
          .loadClass("datadog.trace.instrumentation.gradle.CiVisibilityGradleListener");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Could not load CI Visibility Gradle Listener", e);
    }
  }
}
