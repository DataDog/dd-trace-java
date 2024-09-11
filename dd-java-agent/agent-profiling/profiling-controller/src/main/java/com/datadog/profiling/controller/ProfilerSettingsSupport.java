package com.datadog.profiling.controller;

import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.ProfilingEnablement;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Capture the profiler config first and allow emitting the setting events per each recording. */
public abstract class ProfilerSettingsSupport {
  protected static final class ProfilerActivationSetting {
    public enum Ssi {
      INJECTED_AGENT,
      NONE
    }

    public final ProfilingEnablement enablement;
    public final Ssi ssiMechanism;

    public ProfilerActivationSetting(ProfilingEnablement enablement, Ssi ssiMechanism) {
      this.enablement = enablement;
      this.ssiMechanism = ssiMechanism;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ProfilerActivationSetting that = (ProfilerActivationSetting) o;
      return enablement == that.enablement && ssiMechanism == that.ssiMechanism;
    }

    @Override
    public int hashCode() {
      return Objects.hash(enablement, ssiMechanism);
    }

    @Override
    public String toString() {
      return "ProfilerActivationSetting{"
          + "enablement="
          + enablement
          + ", ssiMechanism="
          + ssiMechanism
          + '}';
    }
  }

  protected static final String JFR_IMPLEMENTATION_KEY = "JFR Implementation";
  protected static final String UPLOAD_PERIOD_KEY = "Upload Period";
  protected static final String UPLOAD_TIMEOUT_KEY = "Upload Timeout";
  protected static final String UPLOAD_COMPRESSION_KEY = "Upload Compression";
  protected static final String ALLOCATION_PROFILING_KEY = "Allocation Profiling";
  protected static final String HEAP_PROFILING_KEY = "Heap Profiling";
  protected static final String FORCE_START_FIRST_KEY = "Force Start-First";
  protected static final String HOTSPOTS_KEY = "Hotspots";
  protected static final String ENDPOINTS_KEY = "Endpoints";
  protected static final String AUXILIARY_PROFILER_KEY = "Auxiliary Profiler";
  protected static final String PERF_EVENTS_PARANOID_KEY = "perf_events_paranoid";
  protected static final String NATIVE_STACKS_KEY = "Native Stacks";
  protected static final String STACK_DEPTH_KEY = "Stack Depth";
  protected static final String SELINUX_STATUS_KEY = "SELinux Status";

  protected static final String DDPROF_UNAVAILABLE_REASON_KEY = "DDProf Unavailable Reason";

  protected static final String SERVICE_INSTRUMENTATION_TYPE = "Service Instrumentation Type";
  protected static final String SERVICE_INJECTION = "Service Injection";
  protected static final String PROFILER_ACTIVATION = "Profiler Activation";
  protected static final String SSI_MECHANISM = "SSI Mechanism";

  protected final int uploadPeriod;
  protected final int uploadTimeout;
  protected final String uploadCompression;
  protected final boolean allocationProfilingEnabled;
  protected final boolean heapProfilingEnabled;
  protected final boolean startForceFirst;
  protected final String templateOverride;
  protected final int exceptionSampleLimit;
  protected final int exceptionHistogramTopItems;
  protected final int exceptionHistogramMaxSize;
  protected final boolean hotspotsEnabled;
  protected final boolean endpointsEnabled;
  protected final String auxiliaryProfiler;
  protected final String perfEventsParanoid;
  protected final boolean hasNativeStacks;
  protected final String seLinuxStatus;
  protected final String serviceInstrumentationType;
  protected final String serviceInjection;

  protected final String ddprofUnavailableReason;

  protected final ProfilerActivationSetting profilerActivationSetting;

  protected final int stackDepth;
  protected final boolean hasJfrStackDepthApplied;

  protected ProfilerSettingsSupport(
      ConfigProvider configProvider,
      String ddprofUnavailableReason,
      boolean hasJfrStackDepthApplied) {
    uploadPeriod =
        configProvider.getInteger(
            ProfilingConfig.PROFILING_UPLOAD_PERIOD,
            ProfilingConfig.PROFILING_UPLOAD_PERIOD_DEFAULT);
    uploadTimeout =
        configProvider.getInteger(
            ProfilingConfig.PROFILING_UPLOAD_TIMEOUT,
            ProfilingConfig.PROFILING_UPLOAD_TIMEOUT_DEFAULT);
    uploadCompression =
        configProvider.getString(
            ProfilingConfig.PROFILING_UPLOAD_COMPRESSION,
            ProfilingConfig.PROFILING_UPLOAD_COMPRESSION_DEFAULT);
    allocationProfilingEnabled =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_ALLOCATION_ENABLED,
            ProfilingSupport.isObjectAllocationSampleAvailable());
    heapProfilingEnabled =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_HEAP_ENABLED, ProfilingConfig.PROFILING_HEAP_ENABLED_DEFAULT);
    startForceFirst =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_START_FORCE_FIRST,
            ProfilingConfig.PROFILING_START_FORCE_FIRST_DEFAULT);
    templateOverride = configProvider.getString(ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE);
    exceptionSampleLimit =
        configProvider.getInteger(
            ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT,
            ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT);
    exceptionHistogramTopItems =
        configProvider.getInteger(
            ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS,
            ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS_DEFAULT);
    exceptionHistogramMaxSize =
        configProvider.getInteger(
            ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE,
            ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE_DEFAULT);
    hotspotsEnabled = configProvider.getBoolean(ProfilingConfig.PROFILING_HOTSPOTS_ENABLED, false);
    endpointsEnabled =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED,
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED_DEFAULT);
    auxiliaryProfiler =
        configProvider.getString(
            ProfilingConfig.PROFILING_AUXILIARY_TYPE, getDefaultAuxiliaryProfiler());
    perfEventsParanoid = readPerfEventsParanoidSetting();
    hasNativeStacks =
        !"no"
            .equalsIgnoreCase(
                configProvider.getString(
                    ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK,
                    configProvider.getString(
                        "profiling.async.cstack",
                        ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK_DEFAULT)));
    stackDepth =
        configProvider.getInteger(
            ProfilingConfig.PROFILING_STACKDEPTH,
            ProfilingConfig.PROFILING_STACKDEPTH_DEFAULT,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_STACKDEPTH);

    seLinuxStatus = getSELinuxStatus();
    this.ddprofUnavailableReason = ddprofUnavailableReason;
    this.hasJfrStackDepthApplied = hasJfrStackDepthApplied;

    serviceInjection = getServiceInjection(configProvider);
    serviceInstrumentationType =
        // usually set via DD_INSTRUMENTATION_INSTALL_TYPE env var
        configProvider.getString("instrumentation.install.type");
    this.profilerActivationSetting = getProfilerActivation(configProvider);
  }

  private static String getServiceInjection(ConfigProvider configProvider) {
    // usually set via DD_INJECTION_ENABLED env var
    return configProvider.getString("injection.enabled");
  }

  static ProfilerActivationSetting getProfilerActivation(ConfigProvider configProvider) {
    return new ProfilerActivationSetting(
        ProfilingEnablement.from(configProvider),
        getServiceInjection(configProvider) != null
            ? ProfilerActivationSetting.Ssi.INJECTED_AGENT
            : ProfilerActivationSetting.Ssi.NONE);
  }

  private String getSELinuxStatus() {
    String value = "Not present";
    if (Platform.isLinux()) {
      try (final TraceScope scope = AgentTracer.get().muteTracing()) {
        ProcessBuilder pb = new ProcessBuilder("getenforce");
        Process process = pb.start();
        // wait for at most 500ms for the process to finish
        // if it takes longer, assume SELinux is not present
        // if the exit value is not 0, assume SELinux is not present
        if (process.waitFor(500, TimeUnit.MILLISECONDS) && process.exitValue() == 0) {
          // we are expecting just a single line output from `getenforce`, so we can avoid draining
          // stdout/stderr asynchronously
          try (BufferedReader reader =
              new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line = reader.readLine();

            if (line != null) {
              value = line.trim();
            }
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException ignored) {
        // nothing to do here, can not execute `getenforce`, let's assume SELinux is not present
      }
    }
    return value;
  }

  private static String getDefaultAuxiliaryProfiler() {
    return Config.get().isDatadogProfilerEnabled()
        ? "ddprof"
        : ProfilingConfig.PROFILING_AUXILIARY_TYPE_DEFAULT;
  }

  /** To be defined in controller specific way. Eg. one could emit JFR events. */
  public abstract void publish();

  private static String readPerfEventsParanoidSetting() {
    String value = "unknown";
    if (Platform.isLinux()) {
      Path perfEventsParanoid = Paths.get("/proc/sys/kernel/perf_event_paranoid");
      try {
        if (Files.exists(perfEventsParanoid)) {
          value = new String(Files.readAllBytes(perfEventsParanoid), StandardCharsets.UTF_8).trim();
        }
      } catch (Exception ignore) {
      }
    }
    return value;
  }
}
