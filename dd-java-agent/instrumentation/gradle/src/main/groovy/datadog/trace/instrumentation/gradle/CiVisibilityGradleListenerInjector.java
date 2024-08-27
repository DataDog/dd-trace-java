package datadog.trace.instrumentation.gradle;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.initialization.ClassLoaderRegistry;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.ServiceRegistry;

public class CiVisibilityGradleListenerInjector {

  private static final Logger LOGGER = Logging.getLogger(CiVisibilityGradleListenerInjector.class);

  public static ClassLoaderRegistry getClassLoaderRegistry(ServiceRegistry[] serviceRegistries) {
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
  public static Class<?> loadCiVisibilityGradleListener(ClassLoaderRegistry classLoaderRegistry) {
    try {
      return classLoaderRegistry
          .getPluginsClassLoader()
          .loadClass("datadog.trace.instrumentation.gradle.CiVisibilityGradleListener");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Could not load CI Visibility Gradle Listener", e);
    }
  }

  /**
   * Performs listener injection for Gradle v8.10+. As the tracer currently uses v8.4, some of the
   * required interfaces are not available at compile time, which is why reflection is used.
   */
  // TODO: once the tracer is bumped to use Gradle v8.10 replace reflection with regular invocations
  public static void injectCiVisibilityGradleListener(
      DefaultServiceRegistry buildScopeServices, ServiceRegistry... parentServices) {
    try {
      ClassLoaderRegistry classLoaderRegistry = getClassLoaderRegistry(parentServices);
      ClassLoader coreApiClassLoader = classLoaderRegistry.getGradleCoreApiClassLoader();
      Class<?> serviceRegistrationActionClass =
          coreApiClassLoader.loadClass("org.gradle.internal.service.ServiceRegistrationAction");

      Object serviceRegistrationAction =
          Proxy.newProxyInstance(
              coreApiClassLoader,
              new Class<?>[] {serviceRegistrationActionClass},
              (proxy, method, args) -> {
                if (method.getName().equals("registerServices")) {
                  ServiceRegistration serviceRegistration = (ServiceRegistration) args[0];
                  Class<?> ciVisibilityGradleListener =
                      CiVisibilityGradleListenerInjector.loadCiVisibilityGradleListener(
                          classLoaderRegistry);
                  serviceRegistration.add(ciVisibilityGradleListener);
                  return null;
                }
                throw new UnsupportedOperationException("Method not implemented");
              });

      Method register =
          DefaultServiceRegistry.class.getMethod("register", serviceRegistrationActionClass);
      register.invoke(buildScopeServices, serviceRegistrationAction);

    } catch (Exception e) {
      LOGGER.warn("Could not inject CI Visibility Gradle listener", e);
    }
  }

  /** Performs listener injection for Gradle v8.3 - 8.9 */
  public static void injectCiVisibilityGradleListenerLegacy(
      DefaultServiceRegistry buildScopeServices, ServiceRegistry... parentServices) {
    try {
      ClassLoaderRegistry classLoaderRegistry =
          CiVisibilityGradleListenerInjector.getClassLoaderRegistry(parentServices);
      Class<?> ciVisibilityGradleListener =
          CiVisibilityGradleListenerInjector.loadCiVisibilityGradleListener(classLoaderRegistry);
      buildScopeServices.register(
          serviceRegistration -> serviceRegistration.add(ciVisibilityGradleListener));
    } catch (Exception e) {
      LOGGER.warn("Could not inject CI Visibility Gradle listener", e);
    }
  }
}
