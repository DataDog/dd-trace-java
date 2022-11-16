package org.jboss.modules;

import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link Module} helper that searches a module's linked dependencies to see if they have access to
 * resources and classes that are not visible from the initial module. This relaxation of modularity
 * rules is sometimes necessary when injecting advice, because it may refer to related packages that
 * would have been imported if the original code had needed them.
 */
public final class ModuleLinkageHelper {

  /** Delegates the resource request to any modules linked as dependencies. */
  public static URL getResource(final Module module, final String resourceName) {
    return searchDirectDependencies(
        module,
        // Uses inner class for predictable name for Instrumenter.Default.helperClassNames()
        new Function<Module, URL>() {
          @Override
          public URL apply(Module input) {
            return input.getResource(resourceName);
          }
        },
        new HashSet<>());
  }

  /** Delegates the class-load request to any modules linked as dependencies. */
  public static Class<?> loadClass(final Module module, final String className) {
    return searchDirectDependencies(
        module,
        // Uses inner class for predictable name for Instrumenter.Default.helperClassNames()
        new Function<Module, Class<?>>() {
          @Override
          public Class<?> apply(Module input) {
            return input.loadModuleClass(className, false);
          }
        },
        new HashSet<>());
  }

  /** Searches a module's direct (module) dependencies. */
  private static <T> T searchDirectDependencies(
      final Module origin, final Function<Module, T> filter, final Set<ModuleIdentifier> visited) {
    // track which modules we've visited to avoid dependency cycles
    visited.add(origin.getIdentifier());
    for (DependencySpec dependency : origin.getDependencies()) {
      if (dependency instanceof ModuleDependencySpec) {
        ModuleIdentifier identifier = ((ModuleDependencySpec) dependency).getIdentifier();
        if (visited.add(identifier)) {
          Module module = origin.getModuleLoader().findLoadedModuleLocal(identifier);
          if (null != module) {
            T result = filter.apply(module);
            if (null != result) {
              return result;
            }
          }
        }
      }
    }
    return null;
  }
}
