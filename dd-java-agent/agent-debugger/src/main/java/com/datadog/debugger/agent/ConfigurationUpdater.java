package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.DebuggerProductChangesListener.LOG_PROBE_PREFIX;
import static com.datadog.debugger.agent.DebuggerProductChangesListener.METRIC_PROBE_PREFIX;
import static com.datadog.debugger.agent.DebuggerProductChangesListener.SPAN_DECORATION_PROBE_PREFIX;
import static com.datadog.debugger.agent.DebuggerProductChangesListener.SPAN_PROBE_PREFIX;

import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.Sampled;
import com.datadog.debugger.probe.Sampling;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.util.ExceptionHelper;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.relocate.api.RatelimitedLogger;
import datadog.trace.util.TagsHelper;
import java.lang.instrument.Instrumentation;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
public class ConfigurationUpdater implements DebuggerContext.ProbeResolver, ConfigurationAcceptor {

  public interface TransformerSupplier {
    DebuggerTransformer supply(
        Config tracerConfig,
        Configuration configuration,
        DebuggerTransformer.InstrumentationListener listener,
        ProbeMetadata probeMetadata,
        DebuggerSink debuggerSink);
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationUpdater.class);
  private static final int MINUTES_BETWEEN_ERROR_LOG = 5;

  private final Instrumentation instrumentation;
  private final TransformerSupplier transformerSupplier;
  private final Lock configurationLock = new ReentrantLock();
  private final EnumMap<Source, Collection<? extends ProbeDefinition>> definitionSources =
      new EnumMap<>(Source.class);
  private volatile Configuration currentConfiguration;
  private DebuggerTransformer currentTransformer;
  private final Map<String, ProbeDefinition> appliedDefinitions = new ConcurrentHashMap<>();
  private final ProbeMetadata probeMetadata = new ProbeMetadata();
  private final DebuggerSink sink;
  private final ClassesToRetransformFinder finder;
  private final String serviceName;
  private final Map<String, InstrumentationResult> instrumentationResults =
      new ConcurrentHashMap<>();
  private final RatelimitedLogger ratelimitedLogger =
      new RatelimitedLogger(LOGGER, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES);

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

  // /!\ Can be called by different threads and concurrently /!\
  // Should throw a runtime exception if there is a problem. The message of
  // the exception will be reported in the next request to the conf service
  @Override
  public void accept(Source source, Collection<? extends ProbeDefinition> definitions) {
    try {
      // 1 有变化，在这里执行
      LOGGER.debug("Received new definitions from {}", source);
      definitionSources.put(source, definitions);
      Configuration newConfiguration = createConfiguration(definitionSources);
      applyNewConfiguration(newConfiguration);
    } catch (RuntimeException e) {
      ExceptionHelper.logException(LOGGER, e, "Error during accepting new debugger configuration:");
      throw e;
    }
  }

  @Override
  public void handleException(String configId, Exception ex) {
    if (configId == null) {
      return;
    }
    ProbeId probeId;
    if (configId.startsWith(LOG_PROBE_PREFIX)) {
      probeId = extractPrefix(LOG_PROBE_PREFIX, configId);
    } else if (configId.startsWith(METRIC_PROBE_PREFIX)) {
      probeId = extractPrefix(METRIC_PROBE_PREFIX, configId);
    } else if (configId.startsWith(SPAN_PROBE_PREFIX)) {
      probeId = extractPrefix(SPAN_PROBE_PREFIX, configId);
    } else if (configId.startsWith(SPAN_DECORATION_PROBE_PREFIX)) {
      probeId = extractPrefix(SPAN_DECORATION_PROBE_PREFIX, configId);
    } else {
      probeId = new ProbeId(configId, 0);
    }
    LOGGER.warn("Error handling probe configuration: {}", configId, ex);
    sink.getProbeStatusSink().addError(probeId, ex);
  }

  ProbeMetadata getProbeMetadata() {
    return probeMetadata;
  }

  private ProbeId extractPrefix(String prefix, String configId) {
    return new ProbeId(configId.substring(prefix.length()), 0);
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
        LOGGER.debug("Applying new probe configuration, changes: {}", changes);
        handleProbesChanges(changes, newConfiguration);
      }
    } finally {
      configurationLock.unlock();
    }
  }

  private Configuration createConfiguration(
      EnumMap<Source, Collection<? extends ProbeDefinition>> sources) {
    Configuration.Builder builder = Configuration.builder();
    for (Collection<? extends ProbeDefinition> definitions : sources.values()) {
      builder.add(definitions);
    }
    return builder.build();
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
      if (def instanceof ExceptionProbe) {
        // do not report received for exception probes
        continue;
      }
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
            Config.get(),
            newConfiguration,
            this::recordInstrumentationProgress,
            probeMetadata,
            sink);
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
  }

  private void retransformClasses(List<Class<?>> classesToBeTransformed) {
    for (Class<?> clazz : classesToBeTransformed) {
      try {
        LOGGER.debug("Re-transforming class: {}", clazz.getTypeName());
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
      probeMetadata.removeProbe(definition.getProbeId().getEncodedId());
    }
    for (ProbeDefinition definition : changes.getAddedDefinitions()) {
      appliedDefinitions.put(definition.getProbeId().getEncodedId(), definition);
    }
    LOGGER.debug("Stored appliedDefinitions: {}", appliedDefinitions.values());
  }

  // /!\ This is called potentially by multiple threads from the instrumented code /!\
  @Override
  public ProbeImplementation resolve(int probeIndex) {
    return probeMetadata.getProbe(probeIndex);
  }

  private static void applyRateLimiter(
      ConfigurationComparer changes, LogProbe.Sampling globalSampling) {
    // ensure rate is up-to-date for all new probes
    for (ProbeDefinition added : changes.getAddedDefinitions()) {
      if (added instanceof Sampled) {
        Sampled probe = (Sampled) added;
        Sampling sampling = probe.getSampling();
        double rate = getDefaultRateLimitPerProbe(probe);
        if (sampling != null && sampling.getEventsPerSecond() != 0) {
          rate = sampling.getEventsPerSecond();
        }
        ProbeRateLimiter.setRate(probe.getId(), rate, probe.isCaptureSnapshot());
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

  private static double getDefaultRateLimitPerProbe(Sampled probe) {
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
