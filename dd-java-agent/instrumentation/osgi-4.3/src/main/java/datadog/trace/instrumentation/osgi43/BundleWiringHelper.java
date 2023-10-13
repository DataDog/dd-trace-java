package datadog.trace.instrumentation.osgi43;

import static org.osgi.framework.wiring.BundleRevision.PACKAGE_NAMESPACE;

import java.net.URL;
import java.util.HashSet;
import java.util.List;
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
    if (null != wiring) {
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
    }
    return SKIP_REQUEST;
  }

  /** Delegates resource request to any direct dependencies (Import-Package, Require-Bundle etc.) */
  public static URL getResource(final Bundle origin, final String resourceName) {
    BundleWiring wiring = (BundleWiring) origin.adapt(BundleWiring.class);
    if (null != wiring) {
      // track which bundles we've visited to avoid dependency cycles
      Set<BundleRevision> visited = new HashSet<>();
      visited.add(wiring.getRevision());
      // check all Import-Package and Require-Bundle dependencies for resources
      List<BundleWire> dependencies = wiring.getRequiredWires(null);
      if (null != dependencies) {
        for (BundleWire dependency : dependencies) {
          BundleWiring provider = dependency.getProviderWiring();
          if (null != provider && visited.add(provider.getRevision())) {
            try {
              URL result = provider.getBundle().getResource(resourceName);
              if (null != result) {
                return result;
              }
            } catch (Exception ignore) {
              // continue search...
            }
          }
        }
      }
    }
    return null;
  }

  /** Delegates class-load request to those direct dependencies that provide a similar package. */
  public static Class<?> loadClass(final Bundle origin, final String className) {
    BundleWiring wiring = (BundleWiring) origin.adapt(BundleWiring.class);
    if (null != wiring) {
      // track which bundles we've visited to avoid dependency cycles
      Set<BundleRevision> visited = new HashSet<>();
      visited.add(wiring.getRevision());
      // check Import-Package dependencies that share a package prefix with the class
      List<BundleWire> dependencies = wiring.getRequiredWires(PACKAGE_NAMESPACE);
      if (null != dependencies) {
        for (BundleWire dependency : dependencies) {
          Object pkg = dependency.getCapability().getAttributes().get(PACKAGE_NAMESPACE);
          if (pkg instanceof String && isRelatedPackage((String) pkg, className)) {
            BundleWiring provider = dependency.getProviderWiring();
            if (null != provider && visited.add(provider.getRevision())) {
              try {
                return provider.getBundle().loadClass(className);
              } catch (Exception ignore) {
                // continue search...
              }
            }
          }
        }
      }
    }
    return null;
  }

  private static boolean isRelatedPackage(String providedPackage, String className) {
    int segmentsMatched = 0;
    for (int i = 0; i < providedPackage.length(); i++) {
      char c = providedPackage.charAt(i);
      if (i >= className.length() || c != className.charAt(i)) {
        return false; // no common package prefix
      }
      if (c == '.' && ++segmentsMatched >= 3) {
        break; // three package segments matched, assume related
      }
    }
    return true;
  }
}
