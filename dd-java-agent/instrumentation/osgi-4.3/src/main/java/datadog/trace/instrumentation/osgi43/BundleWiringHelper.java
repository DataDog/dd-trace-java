package datadog.trace.instrumentation.osgi43;

import static org.osgi.framework.wiring.BundleRevision.PACKAGE_NAMESPACE;

import datadog.trace.api.Config;
import datadog.trace.api.Function;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

/**
 * {@link BundleWiring} helper that searches a bundle's required wires to see if they have access to
 * resources and classes that are not visible from the initial bundle. This relaxation of modularity
 * rules is sometimes necessary when injecting advice, because it may refer to related packages that
 * would have been imported if the original code had needed them.
 */
public final class BundleWiringHelper {

  // how far to take the breadth-first search of required wires (Import-Package, etc.)
  private static final int MAX_DEPTH = Config.get().getOsgiSearchDepth();

  // placeholder when we want byte-buddy to skip the original call and return null
  private static final Object SKIP_REQUEST = new Object();

  /** Probes for the named resource without using any class-loader related methods. */
  public static Object probeResource(final Bundle origin, final String resourceName) {
    URL resource = origin.getEntry(resourceName);
    if (null != resource) {
      return resource;
    }
    // not in the bundle, lets check for a direct import of the containing package
    BundleWiring wiring = (BundleWiring) origin.adapt(BundleWiring.class);
    List<BundleWire> importWires = wiring.getRequiredWires(PACKAGE_NAMESPACE);
    if (null != importWires) {
      int lastSlash = resourceName.lastIndexOf('/');
      if (lastSlash > 0) {
        String pkg = resourceName.substring(0, lastSlash).replace('/', '.');
        for (BundleWire wire : importWires) {
          if (pkg.equals(wire.getCapability().getAttributes().get(PACKAGE_NAMESPACE))) {
            // class resource comes from a transitive import - to avoid cost of finding it, and
            // because classloader matching/probing just checks existence, we return a resource
            // we know exists to stand-in for the real resource
            return origin.getEntry("META-INF/MANIFEST.MF");
          }
        }
      }
    }
    return SKIP_REQUEST;
  }

  /** Delegates the resource request to any bundles wired as dependencies. */
  public static URL getResource(final Bundle origin, final String resourceName) {
    return searchTransitiveWires(
        origin,
        new Function<BundleWiring, URL>() {
          @Override
          public URL apply(final BundleWiring wiring) {
            return wiring.getBundle().getResource(resourceName);
          }
        });
  }

  /** Delegates the class-load request to any bundles wired as dependencies. */
  public static Class<?> loadClass(final Bundle origin, final String className) {
    return searchTransitiveWires(
        origin,
        new Function<BundleWiring, Class<?>>() {
          @Override
          public Class<?> apply(final BundleWiring wiring) {
            try {
              return wiring.getBundle().loadClass(className);
            } catch (ClassNotFoundException e) {
              return null;
            }
          }
        });
  }

  /** Searches any bundles wired as transitive dependencies using the filter to find a match. */
  private static <T> T searchTransitiveWires(
      final Bundle origin, final Function<BundleWiring, T> filter) {
    BundleWiring wiring = (BundleWiring) origin.adapt(BundleWiring.class);

    Queue<BundleWiring> search = new ArrayDeque<>();
    Set<BundleRevision> visited = new HashSet<>();

    search.add(wiring);
    visited.add(wiring.getRevision()); // avoid dependency cycles

    int countToNextDepth = search.size();
    int depth = 1;

    // breadth-first search, keep track of how many bundles to search before the next level
    while (depth <= MAX_DEPTH && !search.isEmpty()) {
      T result = searchDirectWires(search, filter, depth < MAX_DEPTH, visited);
      if (null != result) {
        return result;
      }

      if (--countToNextDepth == 0) {
        if (depth < MAX_DEPTH) {
          countToNextDepth = search.size();
        }
        depth++;
      }
    }

    return null;
  }

  /** Searches a bundle's direct dependencies (Import-Package, Require-Bundle etc.) */
  private static <T> T searchDirectWires(
      final Queue<BundleWiring> search,
      final Function<BundleWiring, T> filter,
      final boolean expandSearch,
      final Set<BundleRevision> visited) {

    List<BundleWire> wires = search.remove().getRequiredWires(null);
    if (null != wires) {
      for (BundleWire wire : wires) {
        BundleWiring wiring = wire.getProviderWiring();
        if (null != wiring && visited.add(wiring.getRevision())) {
          T result = filter.apply(wiring);
          if (null != result) {
            return result;
          } else if (expandSearch) {
            search.add(wiring); // will be processed at the next level of search
          }
        }
      }
    }
    return null;
  }
}
