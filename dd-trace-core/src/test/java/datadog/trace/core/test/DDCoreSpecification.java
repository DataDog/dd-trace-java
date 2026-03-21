package datadog.trace.core.test;

import static net.bytebuddy.description.modifier.FieldManifestation.VOLATILE;
import static net.bytebuddy.description.modifier.Ownership.STATIC;
import static net.bytebuddy.description.modifier.Visibility.PUBLIC;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.none;

import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.tagprocessor.TagsPostProcessorFactory;
import datadog.trace.util.AgentTaskScheduler;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.Transformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class DDCoreSpecification {

  private static final String INST_CONFIG = "datadog.trace.api.InstrumenterConfig";
  private static final String CONFIG = "datadog.trace.api.Config";

  private static Field instConfigInstanceField;
  private static Constructor<?> instConfigConstructor;
  private static Field configInstanceField;
  private static Constructor<?> configConstructor;
  private static boolean configModifiable = false;

  protected static final List<CoreTracer> unclosedTracers = new ArrayList<>();

  private Properties originalSystemProperties;

  static {
    makeConfigInstanceModifiable();
  }

  private static synchronized void makeConfigInstanceModifiable() {
    if (configModifiable) {
      return;
    }
    try {
      installConfigTransformer();

      Class<?> instConfigClass = Class.forName(INST_CONFIG);
      instConfigInstanceField = instConfigClass.getDeclaredField("INSTANCE");
      instConfigInstanceField.setAccessible(true);
      instConfigConstructor = instConfigClass.getDeclaredConstructor();
      instConfigConstructor.setAccessible(true);

      Class<?> configClass = Class.forName(CONFIG);
      configInstanceField = configClass.getDeclaredField("INSTANCE");
      configInstanceField.setAccessible(true);
      configConstructor = configClass.getDeclaredConstructor();
      configConstructor.setAccessible(true);

      configModifiable = true;
    } catch (Exception e) {
      System.err.println("Config will not be modifiable: " + e.getMessage());
    }
  }

  private static void installConfigTransformer() {
    try {
      Instrumentation instrumentation = ByteBuddyAgent.install();
      new AgentBuilder.Default()
          .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
          .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
          .with(
              new AgentBuilder.LocationStrategy.Simple(
                  ClassFileLocator.ForClassLoader.ofSystemLoader()))
          .ignore(none())
          .type(namedOneOf(INST_CONFIG, CONFIG))
          .transform(
              (builder, typeDescription, classLoader, module, pd) ->
                  builder
                      .field(named("INSTANCE"))
                      .transform(Transformer.ForField.withModifiers(PUBLIC, STATIC, VOLATILE)))
          .installOn(instrumentation);
    } catch (IllegalStateException e) {
      // Ignore: ByteBuddy agent already installed or not available
    }
  }

  @BeforeEach
  void setupBase() {
    saveProperties();
    rebuildConfig();
    TagsPostProcessorFactory.withAddInternalTags(false);
    TagsPostProcessorFactory.withAddRemoteHostname(false);
  }

  @AfterEach
  void cleanupBase() {
    for (CoreTracer tracer : unclosedTracers) {
      try {
        tracer.close();
      } catch (Throwable ignored) {
      }
    }
    unclosedTracers.clear();
    try {
      Method shutdownAndReset =
          AgentTaskScheduler.class.getDeclaredMethod(
              "shutdownAndReset", long.class, TimeUnit.class);
      shutdownAndReset.setAccessible(true);
      shutdownAndReset.invoke(null, 10L, TimeUnit.SECONDS);
    } catch (Exception e) {
      // ignore
    }
    restoreProperties();
    rebuildConfig();
    TagsPostProcessorFactory.reset();
  }

  private void saveProperties() {
    originalSystemProperties = new Properties();
    originalSystemProperties.putAll(System.getProperties());
  }

  private void restoreProperties() {
    if (originalSystemProperties != null) {
      Properties copy = new Properties();
      copy.putAll(originalSystemProperties);
      System.setProperties(copy);
    }
  }

  protected void injectSysConfig(String name, String value) {
    String prefixedName = name.startsWith("dd.") ? name : "dd." + name;
    System.setProperty(prefixedName, value);
    rebuildConfig();
  }

  protected void removeSysConfig(String name) {
    String prefixedName = name.startsWith("dd.") ? name : "dd." + name;
    System.clearProperty(prefixedName);
    rebuildConfig();
  }

  protected synchronized void rebuildConfig() {
    if (!configModifiable) {
      return;
    }
    try {
      Object newInstConfig = instConfigConstructor.newInstance();
      instConfigInstanceField.set(null, newInstConfig);
      Object newConfig = configConstructor.newInstance();
      configInstanceField.set(null, newConfig);
    } catch (Exception e) {
      throw new RuntimeException("Failed to rebuild config", e);
    }
  }

  protected CoreTracer.CoreTracerBuilder tracerBuilder() {
    return new AutoCloseableCoreTracerBuilder()
        .statsDClient(StatsDClient.NO_OP)
        .strictTraceWrites(true);
  }

  static class AutoCloseableCoreTracerBuilder extends CoreTracer.CoreTracerBuilder {
    @Override
    public CoreTracer build() {
      CoreTracer tracer = super.build();
      unclosedTracers.add(tracer);
      return tracer;
    }
  }

  protected DDSpan buildSpan(long timestamp, CharSequence spanType, Map<String, Object> tags) {
    return buildSpan(
        timestamp,
        spanType,
        PropagationTags.factory().empty(),
        tags,
        PrioritySampling.SAMPLER_KEEP,
        null);
  }

  protected DDSpan buildSpan(
      long timestamp, String tag, String value, PropagationTags propagationTags) {
    return buildSpan(
        timestamp,
        "fakeType",
        propagationTags,
        Collections.<String, Object>singletonMap(tag, value),
        PrioritySampling.UNSET,
        null);
  }

  protected DDSpan buildSpan(
      long timestamp,
      CharSequence spanType,
      PropagationTags propagationTags,
      Map<String, Object> tags,
      byte prioritySampling,
      Object ciVisibilityContextData) {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    DDSpanContext context =
        new DDSpanContext(
            DDTraceId.ONE,
            1,
            DDSpanId.ZERO,
            null,
            null,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            prioritySampling,
            null,
            Collections.<String, String>emptyMap(),
            null,
            false,
            spanType,
            0,
            tracer.createTraceCollector(DDTraceId.ONE),
            null,
            null,
            ciVisibilityContextData,
            NoopPathwayContext.INSTANCE,
            false,
            propagationTags,
            ProfilingContextIntegration.NoOp.INSTANCE,
            true);
    DDSpan span;
    try {
      Method createMethod =
          DDSpan.class.getDeclaredMethod(
              "create", String.class, long.class, DDSpanContext.class, List.class);
      createMethod.setAccessible(true);
      span = (DDSpan) createMethod.invoke(null, "test", timestamp, context, null);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create DDSpan", e);
    }
    for (Map.Entry<String, Object> entry : tags.entrySet()) {
      span.setTag(entry.getKey(), entry.getValue());
    }
    tracer.close();
    return span;
  }
}
