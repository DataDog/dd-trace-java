package datadog.trace.api.iast.csi;

import static java.util.Collections.emptyList;

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface HasDynamicSupport {

  Logger LOG = LoggerFactory.getLogger(HasDynamicSupport.class);

  abstract class Loader {

    private Loader() {}

    public static volatile Function<ClassLoader, Iterable<Class<? extends HasDynamicSupport>>>
        DYNAMIC_SUPPLIER;

    /**
     * Reads the list of classes from the agent class loader and loads them into the app class
     * loader
     */
    public static Iterable<Class<? extends HasDynamicSupport>> load(final ClassLoader classLoader) {
      if (DYNAMIC_SUPPLIER == null) {
        LOG.debug(
            "Call to load dynamic support helpers too eager for class loader {}", classLoader);
        return emptyList();
      }
      return DYNAMIC_SUPPLIER.apply(classLoader);
    }
  }
}
