package com.datadog.profiling.agent;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import com.datadog.profiling.context.AsyncProfilerTracingContextTrackerFactory;
import com.datadog.profiling.context.JfrTimestampPatch;
import com.datadog.profiling.context.PerSpanTracingContextTrackerFactory;
import com.datadog.profiling.controller.*;
import com.datadog.profiling.uploader.ProfileUploader;
import datadog.trace.api.Checkpointer;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.api.Tracer;
import datadog.trace.api.WithGlobalTracer;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.exceptions.ExceptionSampling;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import datadog.trace.context.ScopeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Profiling agent implementation */
public class ProfilingAgent {

  private static final Logger log = LoggerFactory.getLogger(ProfilingAgent.class);

  private static final Predicate<String> API_KEY_REGEX =
      Pattern.compile("^[0-9a-fA-F]{32}$").asPredicate();

  private static volatile ProfilingSystem profiler;
  private static volatile ProfileUploader uploader;

  /**
   * Main entry point into profiling Note: this must be reentrant because we may want to start
   * profiling before any other tool, and then attempt to start it again at normal time
   */
  public static synchronized void run(ClassLoader agentClasLoader)
      throws IllegalArgumentException, IOException {
    if (profiler == null) {
      final Config config = Config.get();
      final ConfigProvider configProvider = ConfigProvider.getInstance();

      if (!config.isProfilingEnabled()) {
        log.debug("Profiling: disabled");
        return;
      }
      if (config.getApiKey() != null && !API_KEY_REGEX.test(config.getApiKey())) {
        log.info(
            "Profiling: API key doesn't match expected format, expected to get a 32 character hex string. Profiling is disabled.");
        return;
      }
      if (Platform.isJavaVersionAtLeast(9)) {
        JfrTimestampPatch.execute(agentClasLoader);
      }
      log.debug("Scheduling scope event factory registration");
      WithGlobalTracer.registerOrExecute(
          tracer -> {
            try {
              if (Config.get().isProfilingLegacyTracingIntegrationEnabled()) {
                log.debug("Registering scope event factory");
                ScopeListener scopeListener =
                    (ScopeListener)
                        ProfilingAgent.class.getClassLoader()
                            .loadClass("datadog.trace.core.jfr.openjdk.ScopeEventFactory")
                            .getDeclaredConstructor()
                            .newInstance();
                tracer.addScopeListener(scopeListener);
                log.debug("Scope event factory {} has been registered", scopeListener);
              } else if (tracer instanceof AgentTracer.TracerAPI) {
                log.debug("Registering checkpointer");
                Checkpointer checkpointer =
                    (Checkpointer)
                        ProfilingAgent.class.getClassLoader()
                            .loadClass("datadog.trace.core.jfr.openjdk.JFRCheckpointer")
                            .getDeclaredConstructor()
                            .newInstance();
                ((AgentTracer.TracerAPI) tracer).registerCheckpointer(checkpointer);
                log.debug("Checkpointer {} has been registered", checkpointer);
              }
            } catch (Throwable e) {
              if (e instanceof InvocationTargetException) {
                e = e.getCause();
              }
              log.debug("Profiling code hotspots are not available. {}", e.getMessage());
            }
          }
      );
      ExceptionSampling.enableExceptionSampling();

      try {
        final Controller controller = ControllerFactory.createController(configProvider);

        if (AsyncProfilerTracingContextTrackerFactory.isEnabled(configProvider)) {
          AsyncProfilerTracingContextTrackerFactory.register(configProvider);
        } else if (PerSpanTracingContextTrackerFactory.isEnabled(configProvider)) {
          PerSpanTracingContextTrackerFactory.register(configProvider);
        }

        uploader = new ProfileUploader(config, configProvider);

        ProfilingSystemConfig profilingConfig = new ProfilingSystemConfig(configProvider);
        profiler =
            new ProfilingSystem(
                profilingConfig,
                controller,
                uploader::upload);
        profiler.start();
        log.debug("Profiling has started");
        try {
          /*
          Note: shutdown hooks are tricky because JVM holds reference for them forever preventing
          GC for anything that is reachable from it.
          This means that if/when we implement functionality to manually shutdown profiler we would
          need to not forget to add code that removes this shutdown hook from JVM.
           */
          Runtime.getRuntime().addShutdownHook(new ShutdownHook(profiler, uploader));
        } catch (final IllegalStateException ex) {
          // The JVM is already shutting down.
        }
      } catch (final UnsupportedEnvironmentException e) {
        log.warn(e.getMessage());
        log.debug("", e);
      } catch (final ConfigurationException e) {
        log.warn("Failed to initialize profiling agent! " + e.getMessage());
        log.debug("Failed to initialize profiling agent!", e);
      }
    }
  }

  public static void shutdown() {
    shutdown(profiler, uploader, false);
  }

  public static void shutdown(boolean snapshot) {
    shutdown(profiler, uploader, snapshot);
  }

  private static final AtomicBoolean shutDownFlag = new AtomicBoolean();

  private static void shutdown(
      ProfilingSystem profiler, ProfileUploader uploader, boolean snapshot) {
    if (shutDownFlag.compareAndSet(false, true)) {
      if (profiler != null) {
        profiler.shutdown(snapshot);
      }

      if (uploader != null) {
        uploader.shutdown();
      }
    }
  }

  private static class ShutdownHook extends Thread {

    private final WeakReference<ProfilingSystem> profilerRef;
    private final WeakReference<ProfileUploader> uploaderRef;

    private ShutdownHook(final ProfilingSystem profiler, final ProfileUploader uploader) {
      super(AGENT_THREAD_GROUP, "dd-profiler-shutdown-hook");
      profilerRef = new WeakReference<>(profiler);
      uploaderRef = new WeakReference<>(uploader);
    }

    @Override
    public void run() {
      shutdown(profilerRef.get(), uploaderRef.get(), false);
    }
  }
}
