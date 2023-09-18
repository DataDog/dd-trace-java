package com.datadog.debugger.agent;

import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.util.ExceptionHelper;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.util.TagsHelper;
import java.lang.instrument.Instrumentation;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles configuration updates if required by installing a new ClassFileTransformer and triggering
 * re-transformation of required classes
 */
public class ConfigurationUpdater
    implements DebuggerContext.ProbeResolver, DebuggerProductChangesListener.ConfigurationAcceptor {

  public static final int MAX_ALLOWED_METRIC_PROBES = 100;
  public static final int MAX_ALLOWED_LOG_PROBES = 100;
  private static final int MAX_ALLOWED_SPAN_PROBES = 100;
  private static final int MAX_ALLOWED_SPAN_DECORATION_PROBES = 100;

  public interface TransformerSupplier {
    DebuggerTransformer supply(
        Config tracerConfig,
        Configuration configuration,
        DebuggerTransformer.InstrumentationListener listener,
        DebuggerSink debuggerSink);
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationUpdater.class);

  private final Instrumentation instrumentation;
  private final TransformerSupplier transformerSupplier;
  private DebuggerTransformer currentTransformer;
  private final Map<String, ProbeDefinition> appliedDefinitions = new ConcurrentHashMap<>();
  private final DebuggerSink sink;
  private final ClassesToRetransformFinder finder;
  private final String serviceName;

  private final Map<String, InstrumentationResult> instrumentationResults =
      new ConcurrentHashMap<>();

  private Configuration currentConfiguration;

  public ConfigurationUpdater(
      Instrumentation instrumentation,
      TransformerSupplier transformerSupplier,
      Config config,
      ClassesToRetransformFinder finder) {
    this(instrumentation, transformerSupplier, config, new DebuggerSink(config), finder);
  }

  public ConfigurationUpdater(
      Instrumentation instrumentation,
      TransformerSupplier transformerSupplier,
      Config config,
      DebuggerSink sink,
      ClassesToRetransformFinder finder) {
    this.instrumentation = instrumentation;
    this.transformerSupplier = transformerSupplier;
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.sink = sink;
    this.finder = finder;
  }

  // Should be called by only one thread
  // Should throw a runtime exception if there is a problem. The message of
  // the exception will be reported in the next request to the conf service
  public void accept(Configuration configuration) {
    try {
      // handle null configuration
      if (configuration == null) {
        LOGGER.debug("Configuration is null, applying empty configuration with no probes");
        applyNewConfiguration(createEmptyConfiguration());
        return;
      }
      // apply new configuration
      Configuration newConfiguration = applyConfigurationFilters(configuration);
      applyNewConfiguration(newConfiguration);
    } catch (RuntimeException e) {
      ExceptionHelper.logException(LOGGER, e, "Error during accepting new debugger configuration:");
      throw e;
    }
  }

  private void applyNewConfiguration(Configuration newConfiguration) {
    ConfigurationComparer changes =
        new ConfigurationComparer(currentConfiguration, newConfiguration, instrumentationResults);
    currentConfiguration = newConfiguration;
    if (changes.hasProbeRelatedChanges()) {
      LOGGER.info("Applying new probe configuration, changes: {}", changes);
      handleProbesChanges(changes);
    }
    if (changes.hasRateLimitRelatedChanged()) {
      applyRateLimiter(changes);
    }
  }

  private Configuration applyConfigurationFilters(Configuration configuration) {
    Collection<MetricProbe> metricProbes =
        filterProbes(configuration::getMetricProbes, MAX_ALLOWED_METRIC_PROBES);
    Collection<LogProbe> logProbes =
        filterProbes(configuration::getLogProbes, MAX_ALLOWED_LOG_PROBES);
    Collection<SpanProbe> spanProbes =
        filterProbes(configuration::getSpanProbes, MAX_ALLOWED_SPAN_PROBES);
    Collection<SpanDecorationProbe> spanDecorationProbes =
        filterProbes(configuration::getSpanDecorationProbes, MAX_ALLOWED_SPAN_DECORATION_PROBES);
    return new Configuration(
        serviceName,
        metricProbes,
        logProbes,
        spanProbes,
        spanDecorationProbes,
        configuration.getAllowList(),
        configuration.getDenyList(),
        configuration.getSampling());
  }

  private <E extends ProbeDefinition> Collection<E> filterProbes(
      Supplier<Collection<E>> probeSupplier, int maxAllowedProbes) {
    Collection<E> probes = probeSupplier.get();
    if (probes == null) {
      return Collections.emptyList();
    }
    return probes.stream().limit(maxAllowedProbes).collect(Collectors.toList());
  }

  private void handleProbesChanges(ConfigurationComparer changes) {
    removeCurrentTransformer();
    storeDebuggerDefinitions(changes);
    installNewDefinitions();
    reportReceived(changes);
    if (!finder.hasChangedClasses(changes)) {
      return;
    }
    List<Class<?>> changedClasses =
        finder.getAllLoadedChangedClasses(instrumentation.getAllLoadedClasses(), changes);
    retransformClasses(changedClasses);
    // ensures that we have at least re-transformed 1 class
    if (changedClasses.size() > 0) {
      LOGGER.debug("Re-transformation done");
    }
  }

  private void reportReceived(ConfigurationComparer changes) {
    for (ProbeDefinition def : changes.getAddedDefinitions()) {
      sink.addReceived(def.getProbeId());
    }
    for (ProbeDefinition def : changes.getRemovedDefinitions()) {
      sink.removeDiagnostics(def.getProbeId());
    }
  }

  private void installNewDefinitions() {
    DebuggerContext.initClassFilter(new DenyListHelper(currentConfiguration.getDenyList()));
    if (appliedDefinitions.isEmpty()) {
      return;
    }
    // install new probe definitions
    currentTransformer =
        transformerSupplier.supply(
            Config.get(), currentConfiguration, this::recordInstrumentationProgress, sink);
    instrumentation.addTransformer(currentTransformer, true);
    LOGGER.debug("New transformer installed");
  }

  private void recordInstrumentationProgress(
      ProbeDefinition definition, InstrumentationResult instrumentationResult) {
    if (instrumentationResult.isError()) {
      return;
    }
    instrumentationResults.put(definition.getId(), instrumentationResult);
    if (instrumentationResult.isInstalled()) {
      sink.addInstalled(definition.getProbeId());
    } else if (instrumentationResult.isBlocked()) {
      sink.addBlocked(definition.getProbeId());
    }
  }

  private Configuration createEmptyConfiguration() {
    if (currentConfiguration != null) {
      return Configuration.builder()
          .setService(currentConfiguration.getService())
          .addAllowList(currentConfiguration.getAllowList())
          .addDenyList(currentConfiguration.getDenyList())
          .setSampling(currentConfiguration.getSampling())
          .build();
    }
    return Configuration.builder().setService(serviceName).build();
  }

  private void retransformClasses(List<Class<?>> classesToBeTransformed) {
    for (Class<?> clazz : classesToBeTransformed) {
      try {
        LOGGER.info("Re-transforming class: {}", clazz.getTypeName());
        instrumentation.retransformClasses(clazz);
      } catch (Exception ex) {
        ExceptionHelper.logException(LOGGER, ex, "Re-transform error:");
      } catch (Throwable ex) {
        ExceptionHelper.logException(LOGGER, ex, "Re-transform throwable:");
      }
    }
  }

  private void storeDebuggerDefinitions(ConfigurationComparer changes) {
    for (ProbeDefinition definition : changes.getRemovedDefinitions()) {
      appliedDefinitions.remove(definition.getId());
    }
    for (ProbeDefinition definition : changes.getAddedDefinitions()) {
      appliedDefinitions.put(definition.getId(), definition);
    }
    LOGGER.debug("Stored appliedDefinitions: {}", appliedDefinitions.values());
  }

  // /!\ This is called potentially by multiple threads from the instrumented code /!\
  @Override
  public ProbeImplementation resolve(String id, Class<?> callingClass) {
    ProbeDefinition definition = appliedDefinitions.get(id);
    if (definition == null) {
      LOGGER.info(
          "Cannot resolve probe id={}, re-transforming calling class: {}",
          id,
          callingClass.getName());
      retransformClasses(Collections.singletonList(callingClass));
      return null;
    }
    return definition;
  }

  private void applyRateLimiter(ConfigurationComparer changes) {
    Collection<LogProbe> probes = currentConfiguration.getLogProbes();
    if (probes == null) {
      return;
    }
    // ensure rate is up-to-date for all new probes
    for (ProbeDefinition addedDefinitions : changes.getAddedDefinitions()) {
      if (addedDefinitions instanceof LogProbe) {
        LogProbe probe = (LogProbe) addedDefinitions;
        LogProbe.Sampling sampling = probe.getSampling();
        ProbeRateLimiter.setRate(
            probe.getId(),
            sampling != null
                ? sampling.getSnapshotsPerSecond()
                : getDefaultRateLimitPerProbe(probe),
            probe.isCaptureSnapshot());
      }
    }
    // remove rate for all removed probes
    for (ProbeDefinition removedDefinition : changes.getRemovedDefinitions()) {
      if (removedDefinition instanceof LogProbe) {
        ProbeRateLimiter.resetRate(removedDefinition.getId());
      }
    }
    // set global sampling
    LogProbe.Sampling sampling = currentConfiguration.getSampling();
    if (sampling != null) {
      ProbeRateLimiter.setGlobalSnapshotRate(sampling.getSnapshotsPerSecond());
    }
  }

  private double getDefaultRateLimitPerProbe(LogProbe probe) {
    return probe.isCaptureSnapshot()
        ? ProbeRateLimiter.DEFAULT_SNAPSHOT_RATE
        : ProbeRateLimiter.DEFAULT_LOG_RATE;
  }

  private void removeCurrentTransformer() {
    if (currentTransformer == null) {
      return;
    }
    instrumentation.removeTransformer(currentTransformer);
    currentTransformer = null;
  }

  // only visible for tests
  Map<String, ProbeDefinition> getAppliedDefinitions() {
    return appliedDefinitions;
  }
}
