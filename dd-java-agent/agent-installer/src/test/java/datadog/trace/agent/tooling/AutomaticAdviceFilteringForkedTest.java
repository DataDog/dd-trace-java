package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.AdviceStackTest.METADATA_MATCHER;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.memoize.MemoizedMatchers;
import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.utility.JavaModule;
import org.junit.jupiter.api.Test;

@WithConfig(key = "resolver.simple.method.graph", value = "true")
class AutomaticAdviceFilteringForkedTest {
  private static final String TARGET = "datadog.trace.agent.tooling.GeneratedAutomaticAdviceTarget";
  private static final AtomicInteger NEXT_TARGET = new AtomicInteger();

  @Test
  void skipsUnresolvableAdviceForTypeMatchedClassWithoutDeclaredMatch() throws Exception {
    exercise(new MissingMethodInstrumentation(), AdviceStackTest.DeclaredOverride.class, 1, false);
  }

  @Test
  @WithConfig(key = "resolver.simple.method.graph", value = "false")
  void disabledSimpleMethodGraphRetainsOriginalAdviceResolution() throws Exception {
    exercise(new MissingMethodInstrumentation(), AdviceStackTest.DeclaredOverride.class, 1, true);
  }

  @Test
  void retainsAdviceRequiringCompleteMetadataOnLoadAndRetransformation() throws Exception {
    exercise(new MetadataInstrumentation(), AdviceStackTest.Metadata.class, 2, false);
  }

  @Test
  void retainsMetadataAdviceForClassesLoadedBeforeTransformerInstallation() throws Exception {
    exercise(new MetadataInstrumentation(), AdviceStackTest.Metadata.class, 2, false, true);
  }

  @Test
  void typeAdviceRemainsIndependentOfMethodAdviceMatches() throws Exception {
    exercise(new TypeAdviceInstrumentation(), AdviceStackTest.DeclaredOverride.class, 2, false);
  }

  @Test
  void checksSharedMatcherOncePerMethodOnLoadAndRetransformation() throws Exception {
    SharedMissingMatcherInstrumentation module = new SharedMissingMatcherInstrumentation();
    exercise(module, AdviceStackTest.DeclaredOverride.class, 1, false);
    assertEquals(2, module.valueChecks.get());
  }

  @Test
  void preservesEveryAdviceRegisteredWithASharedMatcher() throws Exception {
    exercise(new SharedAdviceInstrumentation(), AdviceStackTest.DeclaredOverride.class, 4, false);
  }

  private void exercise(
      TestInstrumentation module,
      Class<?> source,
      int expected,
      boolean expectAdviceResolutionError)
      throws Exception {
    exercise(module, source, expected, expectAdviceResolutionError, false);
  }

  private void exercise(
      TestInstrumentation module,
      Class<?> source,
      int expected,
      boolean expectAdviceResolutionError,
      boolean loadBeforeInstall)
      throws Exception {
    Instrumentation instrumentation = ByteBuddyAgent.install();
    String targetName = TARGET + NEXT_TARGET.incrementAndGet();
    module.targetName = targetName;
    Class<?> preloaded = loadBeforeInstall ? loadTarget(source, targetName) : null;
    if (preloaded != null) {
      assertValue(preloaded, module.valueParameterTypes, 1);
    }
    AtomicReference<Throwable> error = new AtomicReference<>();
    TypePoolFacade.registerAsSupplier();
    MemoizedMatchers.registerAsSupplier();
    InstrumenterIndex index = InstrumenterIndex.readIndex();
    InstrumenterState.initialize(index.instrumentationCount());
    CombiningTransformerBuilder builder =
        new CombiningTransformerBuilder(
            new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentStrategies.transformerDecorator())
                .with(AgentStrategies.locationStrategy())
                .with(AgentStrategies.poolStrategy())
                .with(AgentStrategies.bufferStrategy())
                .with(AgentStrategies.typeStrategy())
                .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
                .with(
                    new AgentBuilder.Listener.Adapter() {
                      @Override
                      public void onError(
                          String name,
                          ClassLoader loader,
                          JavaModule javaModule,
                          boolean loaded,
                          Throwable throwable) {
                        error.compareAndSet(null, throwable);
                      }
                    })
                .ignore(none()),
            index,
            EnumSet.of(InstrumenterModule.TargetSystem.TRACING),
            false);
    builder.applyInstrumentation(module);
    InstrumenterState.resetDefaultState();
    ClassFileTransformer transformer = builder.installOn(instrumentation);
    SharedTypePools.endInstall();
    try {
      Class<?> target = preloaded != null ? preloaded : loadTarget(source, targetName);
      assertEquals(expectAdviceResolutionError, error.get() != null);
      assertValue(target, module.valueParameterTypes, expected);
      instrumentation.retransformClasses(target);
      assertEquals(expectAdviceResolutionError, error.get() != null);
      assertValue(target, module.valueParameterTypes, expected);
    } finally {
      instrumentation.removeTransformer(transformer);
    }
  }

  private Class<?> loadTarget(Class<?> source, String targetName) {
    return new ByteBuddy()
        .redefine(source)
        .name(targetName)
        .make()
        .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
        .getLoaded();
  }

  private void assertValue(Class<?> target, Class<?>[] parameterTypes, int expected)
      throws Exception {
    Object instance = target.getConstructor().newInstance();
    Object value =
        target
            .getMethod("value", parameterTypes)
            .invoke(instance, new Object[parameterTypes.length]);
    assertEquals(expected, value);
  }

  private abstract static class TestInstrumentation extends InstrumenterModule.Tracing
      implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
    private String targetName;
    private final Class<?>[] valueParameterTypes;

    TestInstrumentation(Class<?>... valueParameterTypes) {
      super("automatic-advice-filtering-test");
      this.valueParameterTypes = valueParameterTypes;
    }

    @Override
    public String instrumentedType() {
      return targetName;
    }

    public static class Muzzle {
      public static ReferenceMatcher create() {
        return ReferenceMatcher.NO_REFERENCES;
      }
    }
  }

  private static final class MissingMethodInstrumentation extends TestInstrumentation {
    public static class Muzzle extends TestInstrumentation.Muzzle {}

    @Override
    public void methodAdvice(MethodTransformer transformer) {
      transformer.applyAdvice(named("absent"), "missing.Advice");
    }
  }

  private static final class MetadataInstrumentation extends TestInstrumentation {
    public static class Muzzle extends TestInstrumentation.Muzzle {}

    MetadataInstrumentation() {
      super(Object.class);
    }

    @Override
    public void methodAdvice(MethodTransformer transformer) {
      transformer.applyAdvice(METADATA_MATCHER, AdviceStackTest.ReturnAdvice.class.getName());
    }
  }

  private static final class SharedMissingMatcherInstrumentation extends TestInstrumentation {
    private final AtomicInteger valueChecks = new AtomicInteger();

    public static class Muzzle extends TestInstrumentation.Muzzle {}

    @Override
    public void methodAdvice(MethodTransformer transformer) {
      transformer.applyAdvices(
          method -> {
            if (method.getName().equals("value")) {
              valueChecks.incrementAndGet();
            }
            return false;
          },
          "missing.FirstAdvice",
          "missing.SecondAdvice",
          "missing.ThirdAdvice");
    }
  }

  private static final class SharedAdviceInstrumentation extends TestInstrumentation {
    public static class Muzzle extends TestInstrumentation.Muzzle {}

    @Override
    public void methodAdvice(MethodTransformer transformer) {
      String advice = AdviceStackTest.ReturnAdvice.class.getName();
      transformer.applyAdvices(named("value"), advice, advice, advice);
    }
  }

  private static final class TypeAdviceInstrumentation extends TestInstrumentation
      implements Instrumenter.HasTypeAdvice {
    public static class Muzzle extends TestInstrumentation.Muzzle {}

    @Override
    public void methodAdvice(MethodTransformer transformer) {
      transformer.applyAdvice(named("absent"), AdviceStackTest.ReturnAdvice.class.getName());
    }

    @Override
    public void typeAdvice(TypeTransformer transformer) {
      transformer.applyAdvice(
          (builder, type, loader, module, protectionDomain) ->
              builder.visit(Advice.to(AdviceStackTest.ReturnAdvice.class).on(named("value"))));
    }
  }
}
