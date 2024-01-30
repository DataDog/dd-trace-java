package com.datadog.debugger.agent;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.debugger.exception.ExceptionProbeManager;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.util.ExceptionHelper;
import datadog.trace.api.Config;
import datadog.trace.api.naming.ServiceNaming;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import java.lang.instrument.Instrumentation;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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
  private final Lock configurationLock = new ReentrantLock();
  private volatile Configuration currentConfiguration;
  private DebuggerTransformer currentTransformer;
  private final Map<String, ProbeDefinition> appliedDefinitions = new ConcurrentHashMap<>();
  private final DebuggerSink sink;
  private final ClassesToRetransformFinder finder;
  private final ExceptionProbeManager exceptionProbeManager;
  private final ServiceNaming serviceNaming;

  private final Map<String, InstrumentationResult> instrumentationResults =
      new ConcurrentHashMap<>();

  public ConfigurationUpdater(
      Instrumentation instrumentation,
      TransformerSupplier transformerSupplier,
      Config config,
      DebuggerSink sink,
      ClassesToRetransformFinder finder,
      ExceptionProbeManager exceptionProbeManager) {
    this.instrumentation = instrumentation;
    this.transformerSupplier = transformerSupplier;
    this.serviceNaming = config.getServiceNaming();
    this.sink = sink;
    this.finder = finder;
    this.exceptionProbeManager = exceptionProbeManager;
  }

  // /!\ Can be called by different threads and concurrently /!\
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
      Configuration newConfiguration =
          applyFiltersAndAddExceptionProbes(configuration, exceptionProbeManager.getProbes());
      applyNewConfiguration(newConfiguration);
    } catch (RuntimeException e) {
      ExceptionHelper.logException(LOGGER, e, "Error during accepting new debugger configuration:");
      throw e;
    }
  }

  public void reapplyCurrentConfig() {
    // TODO make it assync
    accept(currentConfiguration);
  }

  private void applyNewConfiguration(Configuration newConfiguration) {
    configurationLock.lock();
    try {
      Configuration originalConfiguration = currentConfiguration;
      ConfigurationComparer changes =
          new ConfigurationComparer(
              originalConfiguration, newConfiguration, instrumentationResults);
      if (changes.hasRateLimitRelatedChanged()) {
        // apply rate limit config first to avoid racing with execution/instrumentation of log
        // probes
        applyRateLimiter(changes, newConfiguration.getSampling());
      }
      currentConfiguration = newConfiguration;
      if (changes.hasProbeRelatedChanges()) {
        LOGGER.info("Applying new probe configuration, changes: {}", changes);
        handleProbesChanges(changes, newConfiguration);
      }
    } finally {
      configurationLock.unlock();
    }
  }

  private Configuration applyFiltersAndAddExceptionProbes(
      Configuration configuration, Collection<ExceptionProbe> exceptionProbes) {
    Collection<LogProbe> logProbes =
        filterProbes(configuration::getLogProbes, MAX_ALLOWED_LOG_PROBES);
    Collection<MetricProbe> metricProbes =
        filterProbes(configuration::getMetricProbes, MAX_ALLOWED_METRIC_PROBES);
    Collection<SpanProbe> spanProbes =
        filterProbes(configuration::getSpanProbes, MAX_ALLOWED_SPAN_PROBES);
    Collection<SpanDecorationProbe> spanDecorationProbes =
        filterProbes(configuration::getSpanDecorationProbes, MAX_ALLOWED_SPAN_DECORATION_PROBES);
    return Configuration.builder()
        .addLogProbes(logProbes)
        .addExceptionProbes(exceptionProbes)
        .addMetricProbes(metricProbes)
        .addSpanProbes(spanProbes)
        .addSpanDecorationProbes(spanDecorationProbes)
        .addAllowList(configuration.getAllowList())
        .addDenyList(configuration.getDenyList())
        .setSampling(configuration.getSampling())
        .setService(serviceNaming.getSanitizedName().toString())
        .build();
  }

  private <E extends ProbeDefinition> Collection<E> filterProbes(
      Supplier<Collection<E>> probeSupplier, int maxAllowedProbes) {
    Collection<E> probes = probeSupplier.get();
    if (probes == null) {
      return Collections.emptyList();
    }
    return probes.stream().limit(maxAllowedProbes).collect(Collectors.toList());
  }

  private void handleProbesChanges(ConfigurationComparer changes, Configuration newConfiguration) {
    removeCurrentTransformer();
    storeDebuggerDefinitions(changes);
    installNewDefinitions(newConfiguration);
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

  private void installNewDefinitions(Configuration newConfiguration) {
    DebuggerContext.initClassFilter(new DenyListHelper(newConfiguration.getDenyList()));
    if (appliedDefinitions.isEmpty()) {
      return;
    }
    // install new probe definitions
    DebuggerTransformer newTransformer =
        transformerSupplier.supply(
            Config.get(), newConfiguration, this::recordInstrumentationProgress, sink);
    instrumentation.addTransformer(newTransformer, true);
    currentTransformer = newTransformer;
    LOGGER.debug("New transformer installed");
  }

  private void recordInstrumentationProgress(
      ProbeDefinition definition, InstrumentationResult instrumentationResult) {
    if (instrumentationResult.isError()) {
      return;
    }
    instrumentationResults.put(definition.getProbeId().getEncodedId(), instrumentationResult);
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
    return Configuration.builder().setService(serviceNaming.getSanitizedName().toString()).build();
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
      appliedDefinitions.remove(definition.getProbeId().getEncodedId());
    }
    for (ProbeDefinition definition : changes.getAddedDefinitions()) {
      appliedDefinitions.put(definition.getProbeId().getEncodedId(), definition);
    }
    LOGGER.debug("Stored appliedDefinitions: {}", appliedDefinitions.values());
  }

  // /!\ This is called potentially by multiple threads from the instrumented code /!\
  @Override
  public ProbeImplementation resolve(String encodedProbeId) {
    ProbeDefinition definition = appliedDefinitions.get(encodedProbeId);
    if (definition == null) {
      LOGGER.warn(SEND_TELEMETRY, "Cannot resolve probe id=" + encodedProbeId);
    }
    return definition;
  }

  private static void applyRateLimiter(
      ConfigurationComparer changes, LogProbe.Sampling globalSampling) {
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
    if (globalSampling != null) {
      ProbeRateLimiter.setGlobalSnapshotRate(globalSampling.getSnapshotsPerSecond());
    }
  }

  private static double getDefaultRateLimitPerProbe(LogProbe probe) {
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

  Map<String, InstrumentationResult> getInstrumentationResults() {
    return instrumentationResults;
  }
}
