package datadog.trace.instrumentation.spark;

import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Field;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.spark.launcher.SparkAppHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SparkLauncherAdvice {

  private static final Logger log = LoggerFactory.getLogger(SparkLauncherAdvice.class);

  static volatile AgentSpan launcherSpan;

  private static volatile boolean shutdownHookRegistered = false;

  /** Extract SparkLauncher configuration via reflection and set as span tags. */
  private static void setLauncherConfigTags(AgentSpan span, Object launcher) {
    try {
      // SparkLauncher extends AbstractLauncher which has a 'builder' field
      Field builderField = launcher.getClass().getSuperclass().getDeclaredField("builder");
      builderField.setAccessible(true);
      Object builder = builderField.get(launcher);
      if (builder == null) {
        return;
      }

      Class<?> builderClass = builder.getClass();
      // Fields are on AbstractCommandBuilder (parent of SparkSubmitCommandBuilder)
      Class<?> abstractBuilderClass = builderClass.getSuperclass();

      setStringFieldAsTag(span, builder, abstractBuilderClass, "master", "master");
      setStringFieldAsTag(span, builder, abstractBuilderClass, "deployMode", "deploy_mode");
      setStringFieldAsTag(span, builder, abstractBuilderClass, "appName", "application_name");
      setStringFieldAsTag(span, builder, abstractBuilderClass, "mainClass", "main_class");
      setStringFieldAsTag(span, builder, abstractBuilderClass, "appResource", "app_resource");

      // Extract spark conf entries and redact sensitive values
      try {
        Field confField = abstractBuilderClass.getDeclaredField("conf");
        confField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> conf = (Map<String, String>) confField.get(builder);
        if (conf != null) {
          for (Map.Entry<String, String> entry : conf.entrySet()) {
            if (SparkConfAllowList.canCaptureJobParameter(entry.getKey())) {
              String value = SparkConfAllowList.redactValue(entry.getKey(), entry.getValue());
              span.setTag("config." + entry.getKey().replace('.', '_'), value);
            }
          }
        }
      } catch (NoSuchFieldException e) {
        log.debug("Could not find conf field on builder", e);
      }
    } catch (Exception e) {
      log.debug("Failed to extract SparkLauncher configuration", e);
    }
  }

  private static void setStringFieldAsTag(
      AgentSpan span, Object obj, Class<?> clazz, String fieldName, String tagName) {
    try {
      Field field = clazz.getDeclaredField(fieldName);
      field.setAccessible(true);
      Object value = field.get(obj);
      if (value != null) {
        span.setTag(tagName, value.toString());
      }
    } catch (Exception e) {
      log.debug("Could not read field {} from builder", fieldName, e);
    }
  }

  public static synchronized void createLauncherSpan(Object launcher) {
    if (launcherSpan != null) {
      return;
    }

    AgentTracer.TracerAPI tracer = AgentTracer.get();
    AgentSpan span =
        tracer
            .buildSpan("spark.launcher.launch")
            .withSpanType("spark")
            .withResourceName("SparkLauncher.startApplication")
            .start();
    span.setSamplingPriority(PrioritySampling.USER_KEEP, SamplingMechanism.DATA_JOBS);
    setLauncherConfigTags(span, launcher);
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

  public static synchronized void finishSpan(boolean isError, String errorMessage) {
    AgentSpan span = launcherSpan;
    if (span == null) {
      return;
    }
    if (isError) {
      span.setError(true);
      span.setTag(DDTags.ERROR_TYPE, "Spark Launcher Failed");
      span.setTag(DDTags.ERROR_MSG, errorMessage);
    }
    span.finish();
    launcherSpan = null;
  }

  public static synchronized void finishSpanWithThrowable(Throwable throwable) {
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
        @Advice.This Object launcher,
        @Advice.Return SparkAppHandle handle,
        @Advice.Thrown Throwable throwable) {
      createLauncherSpan(launcher);

      if (throwable != null) {
        finishSpanWithThrowable(throwable);
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

  public static class AppHandleListener implements SparkAppHandle.Listener {
    @Override
    public void stateChanged(SparkAppHandle handle) {
      SparkAppHandle.State state = handle.getState();
      AgentSpan span = launcherSpan;
      if (span != null) {
        span.setTag("spark.launcher.app_state", state.toString());

        String appId = handle.getAppId();
        if (appId != null) {
          span.setTag("spark.app_id", appId);
          span.setTag("app_id", appId);
        }

        if (state.isFinal()) {
          if (state == SparkAppHandle.State.FAILED
              || state == SparkAppHandle.State.KILLED
              || state == SparkAppHandle.State.LOST) {
            finishSpan(true, "Application " + state);
          } else {
            finishSpan(false, null);
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
          span.setTag("app_id", appId);
        }
      }
    }
  }
}
