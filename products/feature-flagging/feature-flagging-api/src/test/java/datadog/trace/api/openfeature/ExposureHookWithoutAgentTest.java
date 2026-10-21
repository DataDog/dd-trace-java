package datadog.trace.api.openfeature;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.MutableContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Loads ExposureHook in a class loader that hides DDEvaluator and the Datadog Java agent classes,
 * as in an application without the agent. The normal test classpath always has them, so only a
 * loader like this sees ExposureHook reach for them.
 */
class ExposureHookWithoutAgentTest {

  @Test
  void exposureHookWorksWithoutTheAgentClasses() throws Exception {
    final ClassLoader loader = new AgentlessClassLoader(getClass().getClassLoader());
    Class.forName("datadog.trace.api.openfeature.ExposureHook", true, loader);
    final Class<?> evaluationClass =
        Class.forName("datadog.trace.api.openfeature.ExposureHook$Evaluation", true, loader);
    final Constructor<?> constructor =
        evaluationClass.getDeclaredConstructor(
            EvaluationContext.class, FlagEvaluationDetails.class);
    constructor.setAccessible(true);
    final FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.builder()
            .flagKey("flag")
            .value(1)
            .variant("on")
            .flagMetadata(
                ImmutableMetadata.builder()
                    .addString("allocationKey", "allocation")
                    .addBoolean("__dd_do_log", true)
                    .addBoolean("__dd_exposure_cache_hit", false)
                    .addString("__dd_feature.holdout.key", "q4-global")
                    .build())
            .build();

    final Object evaluation = constructor.newInstance(new MutableContext("user-1"), details);

    assertTrue((Boolean) evaluationClass.getMethod("isExposure").invoke(evaluation));
    assertTrue((Boolean) evaluationClass.getMethod("shouldSend").invoke(evaluation));
    assertEquals(
        singletonMap("holdout.key", "q4-global"),
        evaluationClass.getMethod("getFeatures").invoke(evaluation));
  }

  @Test
  void debugLoggingIsDisabledWithoutAgentConfiguration() throws Exception {
    String previousDebug = System.getProperty("dd.trace.debug");
    System.setProperty("dd.trace.debug", "true");
    try {
      ClassLoader loader = new AgentlessClassLoader(getClass().getClassLoader());
      Class<?> loggerClass = Class.forName(DiagnosticLogger.class.getName(), true, loader);
      Method getLogger = loggerClass.getMethod("getLogger", Class.class);
      getLogger.setAccessible(true);
      Object logger = getLogger.invoke(null, getClass());
      Method isDebugEnabled = loggerClass.getMethod("isDebugEnabled");
      isDebugEnabled.setAccessible(true);
      assertFalse((Boolean) isDebugEnabled.invoke(logger));
    } finally {
      if (previousDebug == null) {
        System.clearProperty("dd.trace.debug");
      } else {
        System.setProperty("dd.trace.debug", previousDebug);
      }
    }
  }

  private static final class AgentlessClassLoader extends ClassLoader {
    AgentlessClassLoader(final ClassLoader parent) {
      super(parent);
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        if (name.startsWith("datadog.trace.bootstrap.config.")
            || name.startsWith("datadog.trace.api.featureflag.")
            || name.startsWith("datadog.trace.api.openfeature.DDEvaluator")) {
          throw new ClassNotFoundException(name);
        }
        if (name.startsWith("datadog.trace.api.openfeature.ExposureHook")
            || name.equals("datadog.trace.api.openfeature.SplitFeatureMetadata")
            || name.equals("datadog.trace.api.openfeature.DiagnosticLogger")) {
          Class<?> loaded = findLoadedClass(name);
          if (loaded == null) {
            final byte[] bytes = classBytes(name);
            loaded = defineClass(name, bytes, 0, bytes.length);
          }
          if (resolve) {
            resolveClass(loaded);
          }
          return loaded;
        }
        return super.loadClass(name, resolve);
      }
    }

    private byte[] classBytes(final String name) throws ClassNotFoundException {
      try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
        if (in == null) {
          throw new ClassNotFoundException(name);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
          out.write(buffer, 0, read);
        }
        return out.toByteArray();
      } catch (final IOException e) {
        throw new ClassNotFoundException(name, e);
      }
    }
  }
}
