package datadog.trace.instrumentation.gradle;

import org.gradle.initialization.ClassLoaderRegistry;
import org.gradle.internal.service.ServiceRegistration;

public class CiVisibilityGradleListenerProvider {
  private final ClassLoaderRegistry classLoaderRegistry;

  public CiVisibilityGradleListenerProvider(ClassLoaderRegistry classLoaderRegistry) {
    this.classLoaderRegistry = classLoaderRegistry;
  }

  public void configure(ServiceRegistration serviceRegistration) {
    Class<?> ciVisibilityGradleListener = loadCiVisibilityGradleListener();
    serviceRegistration.add(ciVisibilityGradleListener);
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
  private Class<?> loadCiVisibilityGradleListener() {
    try {
      return classLoaderRegistry
          .getPluginsClassLoader()
          .loadClass("datadog.trace.instrumentation.gradle.CiVisibilityGradleListener");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Could not load CI Visibility Gradle Listener", e);
    }
  }
}
