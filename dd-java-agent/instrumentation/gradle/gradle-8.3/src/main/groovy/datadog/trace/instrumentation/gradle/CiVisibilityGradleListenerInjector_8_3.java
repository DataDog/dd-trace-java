package datadog.trace.instrumentation.gradle;

import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import org.gradle.api.Action;
import org.gradle.initialization.ClassLoaderRegistry;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiVisibilityGradleListenerInjector_8_3 {

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(DefaultServiceRegistry.class.getClassLoader());
  private static final MethodHandle REGISTER_SERVICE =
      METHOD_HANDLES.method(DefaultServiceRegistry.class, "register", Action.class);

  private static final Logger LOGGER =
      LoggerFactory.getLogger(CiVisibilityGradleListenerInjector_8_3.class);

  /** Performs listener injection for Gradle v8.3 - 8.9 */
  public static void injectCiVisibilityGradleListener(
      DefaultServiceRegistry buildScopeServices, ServiceRegistry... parentServices) {
    try {
      ClassLoaderRegistry classLoaderRegistry =
          CiVisibilityGradleListenerInjector_8_3.getClassLoaderRegistry(parentServices);
      Class<?> ciVisibilityGradleListener =
          CiVisibilityGradleListenerInjector_8_3.loadCiVisibilityGradleListener(
              classLoaderRegistry);
      Action<ServiceRegistration> registrationAction =
          serviceRegistration -> serviceRegistration.add(ciVisibilityGradleListener);
      METHOD_HANDLES.invoke(REGISTER_SERVICE, buildScopeServices, registrationAction);
    } catch (Exception e) {
      LOGGER.warn("Could not inject CI Visibility Gradle listener", e);
    }
  }

  private static ClassLoaderRegistry getClassLoaderRegistry(ServiceRegistry[] serviceRegistries) {
    for (ServiceRegistry serviceRegistry : serviceRegistries) {
      ClassLoaderRegistry classLoaderRegistry =
          (ClassLoaderRegistry) serviceRegistry.find(ClassLoaderRegistry.class);
      if (classLoaderRegistry != null) {
        return classLoaderRegistry;
      }
    }
    throw new RuntimeException(
        "Could not find ClassLoaderRegistry service in " + Arrays.toString(serviceRegistries));
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
