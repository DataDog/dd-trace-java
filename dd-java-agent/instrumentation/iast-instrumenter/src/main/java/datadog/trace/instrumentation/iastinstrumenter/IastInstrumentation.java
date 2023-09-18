package datadog.trace.instrumentation.iastinstrumenter;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumentation;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteSupplier;
import datadog.trace.agent.tooling.bytebuddy.csi.DynamicHelperSupplier;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.csi.HasDynamicSupport;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.instrumentation.iastinstrumenter.telemetry.TelemetryCallSiteSupplier;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(Instrumenter.class)
public class IastInstrumentation extends CallSiteInstrumentation {

  static {
    HasDynamicSupport.Loader.DYNAMIC_SUPPLIER = IastDynamicHelperSupplier.INSTANCE;
  }

  public IastInstrumentation() {
    super("IastInstrumentation");
  }

  @Override
  public ElementMatcher<TypeDescription> callerType() {
    return IastMatcher.INSTANCE;
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.IAST);
  }

  @Override
  protected CallSiteSupplier callSites() {
    return IastCallSiteSupplier.INSTANCE;
  }

  public static final class IastMatcher
      extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {
    public static final IastMatcher INSTANCE = new IastMatcher();

    @Override
    protected boolean doMatch(TypeDescription target) {
      return IastExclusionTrie.apply(target.getName()) != 1;
    }
  }

  public static class IastCallSiteSupplier implements CallSiteSupplier {

    public static final CallSiteSupplier INSTANCE;

    static {
      CallSiteSupplier supplier = new IastCallSiteSupplier(IastCallSites.class);
      final Config config = Config.get();
      final Verbosity verbosity = config.getIastTelemetryVerbosity();
      if (verbosity != Verbosity.OFF) {
        supplier = new TelemetryCallSiteSupplier(verbosity, supplier);
      }
      INSTANCE = supplier;
    }

    private final Class<?> spiInterface;

    public IastCallSiteSupplier(final Class<?> spiInterface) {
      this.spiInterface = spiInterface;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Iterable<CallSites> get() {
      final ClassLoader targetClassLoader = CallSiteInstrumentation.class.getClassLoader();
      return (ServiceLoader<CallSites>) ServiceLoader.load(spiInterface, targetClassLoader);
    }
  }

  public enum IastDynamicHelperSupplier implements DynamicHelperSupplier {
    INSTANCE;

    private static final Logger LOG = LoggerFactory.getLogger(IastDynamicHelperSupplier.class);

    @Override
    public Iterable<Class<? extends HasDynamicSupport>> apply(final ClassLoader classLoader) {
      final Set<String> lines = readClasses(IastInstrumentation.class.getClassLoader());
      return loadClasses(lines, classLoader);
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends HasDynamicSupport>> loadClasses(
        final Set<String> lines, final ClassLoader loader) {
      final List<Class<? extends HasDynamicSupport>> result = new ArrayList<>(lines.size());
      for (final String line : lines) {
        try {
          result.add((Class<? extends HasDynamicSupport>) loader.loadClass(line));
        } catch (final Throwable e) {
          LOG.debug("Error loading dynamic type {}", line, e);
        }
      }
      return result;
    }

    private static Set<String> readClasses(final ClassLoader classLoader) {
      final Set<String> lines = new LinkedHashSet<>();
      try {
        final Enumeration<URL> urls =
            classLoader.getResources("META-INF/services/" + HasDynamicSupport.class.getName());
        while (urls.hasMoreElements()) {
          try (BufferedReader reader =
              new BufferedReader(
                  new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
              lines.add(line);
              line = reader.readLine();
            }
          }
        }
      } catch (Exception e) {
        LOG.error("Failed to load call sites with dynamic support", e);
      }
      return lines;
    }
  }
}
