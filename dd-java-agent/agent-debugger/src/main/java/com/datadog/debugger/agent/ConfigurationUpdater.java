package com.datadog.debugger.agent;

import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.util.ExceptionHelper;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.SummaryBuilder;
import datadog.trace.util.TagsHelper;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
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

  public interface TransformerSupplier {
    DebuggerTransformer supply(
        Config tracerConfig,
        Configuration configuration,
        DebuggerTransformer.InstrumentationListener listener);
  }

  private static final Logger log = LoggerFactory.getLogger(ConfigurationUpdater.class);

  private final Instrumentation instrumentation;
  private final TransformerSupplier transformerSupplier;
  private DebuggerTransformer currentTransformer;
  private final Map<String, ProbeDefinition> appliedDefinitions = new ConcurrentHashMap<>();
  private final EnvironmentAndVersionChecker envAndVersionCheck;
  private final DebuggerSink sink;
  private final String serviceName;

  private final Map<String, InstrumentationResult> instrumentationResults =
      new ConcurrentHashMap<>();

  private Configuration currentConfiguration;

  public ConfigurationUpdater(
      Instrumentation instrumentation, TransformerSupplier transformerSupplier, Config config) {
    this(instrumentation, transformerSupplier, config, new DebuggerSink(config));
  }

  public ConfigurationUpdater(
      Instrumentation instrumentation,
      TransformerSupplier transformerSupplier,
      Config config,
      DebuggerSink sink) {
    this.instrumentation = instrumentation;
    this.transformerSupplier = transformerSupplier;
    this.envAndVersionCheck = new EnvironmentAndVersionChecker(config);
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.sink = sink;
  }

  // Should be called by only one thread
  // Should throw a runtime exception if there is a problem. The message of
  // the exception will be reported in the next request to the conf service
  public void accept(Configuration configuration) {
    try {
      // handle null configuration
      if (configuration == null) {
        log.debug("configuration is null, apply empty configuration with no probes");
        applyNewConfiguration(createEmptyConfiguration());
        return;
      }
      // apply new configuration
      Configuration newConfiguration = applyConfigurationFilters(configuration);
      applyNewConfiguration(newConfiguration);
    } catch (RuntimeException e) {
      ExceptionHelper.logException(log, e, "Error during accepting new debugger configuration:");
      throw e;
    }
  }

  private void applyNewConfiguration(Configuration newConfiguration) {
    ConfigurationComparer changes =
        new ConfigurationComparer(currentConfiguration, newConfiguration, instrumentationResults);
    currentConfiguration = newConfiguration;
    if (changes.hasProbeRelatedChanges()) {
      log.info("Applying new probe configuration, changes: {}", changes);
      handleProbesChanges(changes);
    }
    if (changes.hasRateLimitRelatedChanged()) {
      applyRateLimiter(changes);
    }
  }

  private Configuration applyConfigurationFilters(Configuration configuration) {
    Collection<MetricProbe> metricProbes =
        filterProbes(
            configuration::getMetricProbes, MetricProbe::isActive, MAX_ALLOWED_METRIC_PROBES);
    Collection<LogProbe> logProbes =
        filterProbes(configuration::getLogProbes, LogProbe::isActive, MAX_ALLOWED_LOG_PROBES);
    logProbes = mergeDuplicatedProbes(logProbes);
    Collection<SpanProbe> spanProbes =
        filterProbes(configuration::getSpanProbes, SpanProbe::isActive, MAX_ALLOWED_SPAN_PROBES);
    return new Configuration(
        serviceName,
        metricProbes,
        logProbes,
        spanProbes,
        configuration.getAllowList(),
        configuration.getDenyList(),
        configuration.getSampling());
  }

  private <E extends ProbeDefinition> Collection<E> filterProbes(
      Supplier<Collection<E>> probeSupplier, Predicate<E> isActive, int maxAllowedProbes) {
    Collection<E> probes = probeSupplier.get();
    if (probes == null) {
      return Collections.emptyList();
    }
    return probes.stream()
        .filter(isActive)
        .filter(envAndVersionCheck::isEnvAndVersionMatch)
        .limit(maxAllowedProbes)
        .collect(Collectors.toList());
  }

  Collection<LogProbe> mergeDuplicatedProbes(Collection<LogProbe> probes) {
    Map<Where, LogProbe> mergedProbes = new HashMap<>();
    for (LogProbe probe : probes) {
      ProbeDefinition existingProbe = mergedProbes.putIfAbsent(probe.getWhere(), probe);
      if (existingProbe != null) {
        existingProbe.addAdditionalProbe(probe);
      }
    }
    return new ArrayList<>(mergedProbes.values());
  }

  private void handleProbesChanges(ConfigurationComparer changes) {
    removeCurrentTransformer();
    storeDebuggerDefinitions(changes);
    installNewDefinitions();
    reportReceived(changes);
    if (!changes.hasChangedClasses()) {
      return;
    }
    List<Class<?>> changedClasses =
        changes.getAllLoadedChangedClasses(instrumentation.getAllLoadedClasses());
    retransformClasses(changedClasses);
    // ensures that we have at least re-transformed 1 class
    if (changedClasses.size() > 0) {
      log.debug("Re-transformation done");
    }
  }

  private void reportReceived(ConfigurationComparer changes) {
    for (ProbeDefinition def : changes.getAddedDefinitions()) {
      def.getAllProbeIds().forEach(sink::addReceived);
    }
    for (ProbeDefinition def : changes.getRemovedDefinitions()) {
      def.getAllProbeIds().forEach(sink::removeDiagnostics);
    }
  }

  private boolean installNewDefinitions() {
    DebuggerContext.initClassFilter(new DenyListHelper(currentConfiguration.getDenyList()));
    if (!appliedDefinitions.isEmpty()) {
      // install new probe definitions
      currentTransformer =
          transformerSupplier.supply(
              Config.get(), currentConfiguration, this::recordInstrumentationProgress);
      instrumentation.addTransformer(currentTransformer, true);
      return true;
    }
    return false;
  }

  private void recordInstrumentationProgress(
      ProbeDefinition definition, InstrumentationResult instrumentationResult) {
    instrumentationResults.put(definition.getId(), instrumentationResult);
    if (instrumentationResult.isInstalled()) {
      definition.getAllProbeIds().forEach(sink::addInstalled);
    } else if (instrumentationResult.isBlocked()) {
      definition.getAllProbeIds().forEach(sink::addBlocked);
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
        log.info("re-transforming {}", clazz.getCanonicalName());
        instrumentation.retransformClasses(clazz);
      } catch (Exception ex) {
        ExceptionHelper.logException(log, ex, "re-transform error:");
      } catch (Throwable ex) {
        ExceptionHelper.logException(log, ex, "re-transform throwable:");
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
    log.debug("stored appliedDefinitions: {}", appliedDefinitions.values());
  }

  // /!\ This is called potentially by multiple threads from the instrumented code /!\
  @Override
  public Snapshot.ProbeDetails resolve(String id, Class<?> callingClass) {
    ProbeDefinition definition = appliedDefinitions.get(id);
    if (definition == null) {
      log.info("Cannot resolve probe, re-transforming calling class: {}", callingClass.getName());
      retransformClasses(Collections.singletonList(callingClass));
      return null;
    }
    String type = definition.getWhere().getTypeName();
    String method = definition.getWhere().getMethodName();
    String file = definition.getWhere().getSourceFile();
    String[] probeLines = definition.getWhere().getLines();
    InstrumentationResult result = instrumentationResults.get(definition.getId());
    if (result != null) {
      type = result.getTypeName();
      method = result.getMethodName();
    }
    List<String> lines = probeLines != null ? Arrays.asList(probeLines) : null;
    return convertToProbeDetails(definition, new Snapshot.ProbeLocation(type, method, file, lines));
  }

  private Snapshot.ProbeDetails convertToProbeDetails(
      ProbeDefinition probe, Snapshot.ProbeLocation location) {
    SummaryBuilder summaryBuilder;
    ProbeCondition probeCondition;
    if (probe instanceof LogProbe) {
      summaryBuilder = new LogMessageTemplateSummaryBuilder((LogProbe) probe);
      probeCondition = null;
    } else {
      log.warn("definition id={} has unsupported probe type: {}", probe.getId(), probe.getClass());
      return null;
    }
    return new Snapshot.ProbeDetails(
        probe.getId(),
        location,
        ProbeDefinition.MethodLocation.convert(probe.getEvaluateAt()),
        probeCondition,
        probe.concatTags(),
        summaryBuilder,
        probe.getAdditionalProbes().stream()
            .map(relatedProbe -> convertToProbeDetails(relatedProbe, location))
            .collect(Collectors.toList()));
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
            probe.getId(), sampling != null ? sampling.getSnapshotsPerSecond() : 1.0);
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
      ProbeRateLimiter.setGlobalRate(sampling.getSnapshotsPerSecond());
    }
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
