package datadog.trace.agent.tooling;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
import org.junit.jupiter.api.Test;

class AdviceStackTest {
  static final ElementMatcher<MethodDescription> METADATA_MATCHER =
      method ->
          method.getName().equals("value")
              && method
                  .getParameters()
                  .get(0)
                  .getDeclaredAnnotations()
                  .isAnnotationPresent(Marker.class)
              && method.getExceptionTypes().getOnly().asErasure().represents(IOException.class)
              && method.getTypeVariables().getOnly().getSymbol().equals("T");

  @Test
  void skipsAdviceResolutionWhenNoDeclaredMethodMatches() {
    TypeDescription target = new TypeDescription.ForLoadedType(InheritedOnly.class);
    DynamicType.Builder<?> builder = new ByteBuddy().redefine(InheritedOnly.class);
    AdviceStack stack =
        new AdviceStack(
            singletonList(
                new AgentBuilder.Transformer.ForAdvice().advice(named("value"), "missing.Advice")),
            named("value"),
            0);

    assertSame(builder, transform(stack, builder, target));
    // No advice resolution is needed, and the inherited implementation remains executable.
    assertEquals(1, new InheritedOnly().value());
  }

  @Test
  void preservesAdviceOnDeclaredOverride() throws Exception {
    Class<?> transformed = transform(DeclaredOverride.class, named("value"), ReturnAdvice.class);
    assertEquals(
        2, transformed.getMethod("value").invoke(transformed.getConstructor().newInstance()));
  }

  @Test
  void preservesMatchersUsingParameterAnnotationsThrowsAndGenericSignatures() throws Exception {
    Class<?> transformed = transform(Metadata.class, METADATA_MATCHER, ReturnAdvice.class);
    assertEquals(
        2,
        transformed
            .getMethod("value", Object.class)
            .invoke(transformed.getConstructor().newInstance(), new Object()));
  }

  @Test
  void preservesConstructorAdvice() throws Exception {
    Class<?> transformed = transform(Initialized.class, isConstructor(), FieldAdvice.class);
    assertEquals(2, transformed.getField("value").get(transformed.getConstructor().newInstance()));
  }

  @Test
  void preservesTypeInitializerAdvice() throws Exception {
    Class<?> transformed =
        transform(Initialized.class, isTypeInitializer(), StaticFieldAdvice.class);
    assertEquals(2, transformed.getField("initialized").get(null));
  }

  @Test
  void matcherFailureRetainsOriginalAdviceBehavior() throws Exception {
    ElementMatcher<MethodDescription> throwsDuringPrefilter =
        method -> {
          throw new NoClassDefFoundError("missing.Metadata");
        };
    AgentBuilder.Transformer advice = advice(named("value"), ReturnAdvice.class);
    AdviceStack stack = new AdviceStack(singletonList(advice), throwsDuringPrefilter, 0);
    Class<?> transformed = materialize(DeclaredOverride.class, stack);
    assertEquals(
        2, transformed.getMethod("value").invoke(transformed.getConstructor().newInstance()));
  }

  @Test
  void preservesPrerequisitesAndEvaluatesMatcherAfterTheirEffects() throws Exception {
    AtomicBoolean initialized = new AtomicBoolean();
    AgentBuilder.Transformer prerequisite =
        (builder, type, loader, module, protectionDomain) -> {
          initialized.set(true);
          return builder.visit(Advice.to(ReturnAdvice.class).on(named("value")));
        };
    ElementMatcher<MethodDescription> absent =
        method -> {
          // A prerequisite can initialize helpers that subsequent matchers depend on.
          assertTrue(initialized.get());
          return false;
        };
    AdviceStack stack =
        new AdviceStack(
            asList(
                prerequisite,
                new AgentBuilder.Transformer.ForAdvice().advice(named("absent"), "missing.Advice")),
            absent,
            1);
    Class<?> transformed = materialize(DeclaredOverride.class, stack);
    assertEquals(
        2, transformed.getMethod("value").invoke(transformed.getConstructor().newInstance()));
  }

  private Class<?> transform(
      Class<?> target, ElementMatcher<? super MethodDescription> matcher, Class<?> advice) {
    return materialize(target, new AdviceStack(singletonList(advice(matcher, advice)), matcher, 0));
  }

  private AgentBuilder.Transformer advice(
      ElementMatcher<? super MethodDescription> matcher, Class<?> advice) {
    return new AgentBuilder.Transformer.ForAdvice()
        .include(getClass().getClassLoader())
        .advice(matcher, advice.getName());
  }

  private Class<?> materialize(Class<?> target, AdviceStack stack) {
    return transform(
            stack, new ByteBuddy().redefine(target), new TypeDescription.ForLoadedType(target))
        .make()
        .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
        .getLoaded();
  }

  private DynamicType.Builder<?> transform(
      AdviceStack stack, DynamicType.Builder<?> builder, TypeDescription target) {
    return stack.transform(
        builder, target, getClass().getClassLoader(), JavaModule.UNSUPPORTED, null);
  }

  public static class Parent {
    public int value() {
      return 1;
    }
  }

  public static class InheritedOnly extends Parent {}

  public static class DeclaredOverride extends Parent {
    @Override
    public int value() {
      return 1;
    }
  }

  public static class Metadata {
    public <T> int value(@Marker T argument) throws IOException {
      return 1;
    }
  }

  @Retention(RetentionPolicy.RUNTIME)
  public @interface Marker {}

  public static class Initialized {
    public int value = 1;
    public static int initialized = 1;
  }

  public static class ReturnAdvice {
    @Advice.OnMethodExit
    public static void exit(@Advice.Return(readOnly = false) int result) {
      result++;
    }
  }

  public static class FieldAdvice {
    @Advice.OnMethodExit
    public static void exit(@Advice.FieldValue(value = "value", readOnly = false) int value) {
      value++;
    }
  }

  public static class StaticFieldAdvice {
    @Advice.OnMethodExit
    public static void exit(
        @Advice.FieldValue(value = "initialized", readOnly = false) int initialized) {
      initialized++;
    }
  }
}
