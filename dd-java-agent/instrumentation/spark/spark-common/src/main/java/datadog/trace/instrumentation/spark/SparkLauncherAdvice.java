package datadog.trace.instrumentation.spark;

import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import org.apache.spark.launcher.SparkAppHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SparkLauncherAdvice {

  private static final Logger log = LoggerFactory.getLogger(SparkLauncherAdvice.class);

  /** The launcher span, accessible from SparkExitAdvice via reflection. */
  public static volatile AgentSpan launcherSpan;

  private static volatile boolean shutdownHookRegistered = false;

  public static synchronized void createLauncherSpan(String resource) {
    if (launcherSpan != null) {
      return;
    }

    AgentTracer.TracerAPI tracer = AgentTracer.get();
    AgentSpan span =
        tracer
            .buildSpan("spark.launcher.launch")
            .withSpanType("spark")
            .withResourceName(resource)
            .start();
    span.setSamplingPriority(PrioritySampling.USER_KEEP, SamplingMechanism.DATA_JOBS);
    launcherSpan = span;

    if (!shutdownHookRegistered) {
      shutdownHookRegistered = true;
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    synchronized (SparkLauncherAdvice.class) {
                      AgentSpan s = launcherSpan;
                      if (s != null) {
                        log.info("Finishing spark.launcher span from shutdown hook");
                        s.finish();
                        launcherSpan = null;
                      }
                    }
                  }));
    }
  }

  public static synchronized void finishLauncherSpan(int exitCode) {
    AgentSpan span = launcherSpan;
    if (span == null) {
      return;
    }
    if (exitCode != 0) {
      span.setError(true);
      span.setTag(DDTags.ERROR_TYPE, "Spark Launcher Failed with exit code " + exitCode);
    }
    span.finish();
    launcherSpan = null;
  }

  public static synchronized void finishLauncherSpan(Throwable throwable) {
    AgentSpan span = launcherSpan;
    if (span == null) {
      return;
    }
    if (throwable != null) {
      span.addThrowable(throwable);
    }
    span.finish();
    launcherSpan = null;
  }

  public static class StartApplicationAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(
        @Advice.Return SparkAppHandle handle, @Advice.Thrown Throwable throwable) {
      createLauncherSpan("SparkLauncher.startApplication");

      if (throwable != null) {
        AgentSpan span = launcherSpan;
        if (span != null) {
          span.addThrowable(throwable);
          span.finish();
          launcherSpan = null;
        }
        return;
      }

      if (handle != null) {
        try {
          handle.addListener(new AppHandleListener());
        } catch (Exception e) {
          log.debug("Failed to register SparkAppHandle listener", e);
        }
      }
    }
  }

  public static class LaunchAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Thrown Throwable throwable) {
      createLauncherSpan("SparkLauncher.launch");

      if (throwable != null) {
        AgentSpan span = launcherSpan;
        if (span != null) {
          span.addThrowable(throwable);
          span.finish();
          launcherSpan = null;
        }
      }
    }
  }

  static class AppHandleListener implements SparkAppHandle.Listener {
    @Override
    public void stateChanged(SparkAppHandle handle) {
      SparkAppHandle.State state = handle.getState();
      AgentSpan span = launcherSpan;
      if (span != null) {
        span.setTag("spark.launcher.app_state", state.toString());

        String appId = handle.getAppId();
        if (appId != null) {
          span.setTag("spark.app_id", appId);
        }

        if (state.isFinal()) {
          if (state == SparkAppHandle.State.FAILED
              || state == SparkAppHandle.State.KILLED
              || state == SparkAppHandle.State.LOST) {
            span.setError(true);
            span.setTag(DDTags.ERROR_TYPE, "Spark Application " + state);
          }
        }
      }
    }

    @Override
    public void infoChanged(SparkAppHandle handle) {
      AgentSpan span = launcherSpan;
      if (span != null) {
        String appId = handle.getAppId();
        if (appId != null) {
          span.setTag("spark.app_id", appId);
        }
      }
    }
  }
}
