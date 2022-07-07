package com.datadog.profiling.auxiliary;

import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.utils.ProfilingMode;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.ServiceLoader;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A pluggable auxiliary profiler.<br>
 * An auxiliary profiler is an external profiler implementation which provides an added value on top
 * of the built in JFR. An example of such auxiliary profiler would be <a
 * href="https://github.com/jvm-profiling-tools/async-profiler">Async Profiler</a> <br>
 * <br>
 * The profiler implementations must extend {@linkplain AuxiliaryImplementation} interface and are
 * instantiated via an {@linkplain AuxiliaryImplementation.Provider} instance registered in {@code
 * META-INF/services}.<br>
 * The actual auxiliary implementaion is selected via setting the {@linkplain
 * ProfilingConfig#PROFILING_AUXILIARY_TYPE} configuration key to a value which is recognized by a
 * particular implementation provider.
 */
public final class AuxiliaryProfiler {
  Logger log = LoggerFactory.getLogger(AuxiliaryProfiler.class);

  private static final class Singleton {
    private static final AuxiliaryProfiler INSTANCE = new AuxiliaryProfiler();
  }

  private final AuxiliaryImplementation implementation;

  private AuxiliaryProfiler() {
    this(ConfigProvider.getInstance());
  }

  AuxiliaryProfiler(ConfigProvider configProvider) {
    String auxilliaryType =
        configProvider.getString(
            ProfilingConfig.PROFILING_AUXILIARY_TYPE,
            ProfilingConfig.PROFILING_AUXILIARY_TYPE_DEFAULT);
    log.debug("Requested auxiliary profiler: {}", auxilliaryType);
    AuxiliaryImplementation impl = AuxiliaryImplementation.NULL;
    log.debug("Iterating auxiliary implementation providers");
    for (AuxiliaryImplementation.Provider provider :
        ServiceLoader.load(
            AuxiliaryImplementation.Provider.class, AuxiliaryProfiler.class.getClassLoader())) {
      log.debug(
          "Checking auxiliary implementation {}: {}",
          provider,
          provider.canProvide(auxilliaryType));
      if (provider.canProvide(auxilliaryType)) {
        impl = provider.provide(configProvider);
        break;
      }
    }
    this.implementation = impl != null ? impl : AuxiliaryImplementation.NULL;
  }

  AuxiliaryProfiler(AuxiliaryImplementation impl) {
    this.implementation = impl;
  }

  /**
   * Retrieve the signleton instance using the implementation specified in the agent configuration.
   *
   * @return the signleton instance using the implementation specified in the * agent configuration
   */
  public static AuxiliaryProfiler getInstance() {
    return Singleton.INSTANCE;
  }

  /**
   * Check whether the auxiliary profiler is enabled.<br>
   * In order for the profiler to be enabled there must be at least one implementation registered
   * and the configuration must be set to use one of the registered implementations.
   *
   * @return {@literal true} if the auxiliary profiler is enabled
   */
  public boolean isEnabled() {
    return implementation.isAvailable();
  }

  /**
   * Start the auxiliary recording
   *
   * @return the corresponding {@linkplain OngoingRecording}
   */
  public OngoingRecording start() {
    return implementation.start();
  }

  /**
   * Stop the ongoing recording
   *
   * @param recording the ongoing recording
   */
  public void stop(OngoingRecording recording) {
    implementation.stop(recording);
  }

  /**
   * Query the currently enabled {@link ProfilingMode profiling modes}
   *
   * @return the currently enabled {@link ProfilingMode profiling modes}
   */
  public Set<ProfilingMode> enabledModes() {
    return implementation.enabledModes();
  }
}
