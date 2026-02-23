package datadog.trace.plugin.csi.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AfterSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AllArgsSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification;
import datadog.trace.plugin.csi.util.Types;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class AsmSpecificationBuilderTest extends BaseCsiPluginTest {

  static class NonCallSite {}

  @Test
  void testSpecificationBuilderForNonCallSite() {
    File advice = fetchClass(NonCallSite.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    Optional<CallSiteSpecification> result = specificationBuilder.build(advice);

    assertFalse(result.isPresent());
  }

  @CallSite(spi = WithSpiClass.Spi.class)
  static class WithSpiClass {
    interface Spi {}
  }

  @Test
  void testSpecificationBuilderWithCustomSpiClass() {
    File advice = fetchClass(WithSpiClass.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(
        Arrays.asList(Type.getType(WithSpiClass.Spi.class)), Arrays.asList(result.getSpi()));
  }

  @CallSite(
      spi = CallSites.class,
      helpers = {HelpersAdvice.SampleHelper1.class, HelpersAdvice.SampleHelper2.class})
  static class HelpersAdvice {
    static class SampleHelper1 {}

    static class SampleHelper2 {}
  }

  @Test
  void testSpecificationBuilderWithCustomHelperClasses() {
    File advice = fetchClass(HelpersAdvice.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    List<Type> helpers = Arrays.asList(result.getHelpers());
    assertTrue(
        helpers.containsAll(
            Arrays.asList(
                Type.getType(HelpersAdvice.class),
                Type.getType(HelpersAdvice.SampleHelper1.class),
                Type.getType(HelpersAdvice.SampleHelper2.class))));
  }

  @CallSite(spi = CallSites.class)
  static class BeforeAdvice {
    @CallSite.Before(
        "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)")
    static void before(
        @CallSite.This String self,
        @CallSite.Argument String regexp,
        @CallSite.Argument String replacement) {}
  }

  @Test
  void testSpecificationBuilderForBeforeAdvice() {
    File advice = fetchClass(BeforeAdvice.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(BeforeAdvice.class.getName(), result.getClazz().getClassName());
    BeforeSpecification beforeSpec = (BeforeSpecification) findAdvice(result, "before");
    assertNotNull(beforeSpec);
    assertEquals(
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
        beforeSpec.getAdvice().getMethodType().getDescriptor());
    assertEquals(
        "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)",
        beforeSpec.getSignature());
    assertNotNull(beforeSpec.findThis());
    assertNull(beforeSpec.findReturn());
    assertNull(beforeSpec.findAllArguments());
    assertNull(beforeSpec.findInvokeDynamicConstants());
    List<Integer> arguments = getArguments(beforeSpec);
    assertEquals(Arrays.asList(0, 1), arguments);
  }

  @CallSite(spi = CallSites.class)
  static class AroundAdvice {
    @CallSite.Around(
        "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)")
    static String around(
        @CallSite.This String self,
        @CallSite.Argument String regexp,
        @CallSite.Argument String replacement) {
      return self.replaceAll(regexp, replacement);
    }
  }

  @Test
  void testSpecificationBuilderForAroundAdvice() {
    File advice = fetchClass(AroundAdvice.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(AroundAdvice.class.getName(), result.getClazz().getClassName());
    AroundSpecification aroundSpec = (AroundSpecification) findAdvice(result, "around");
    assertNotNull(aroundSpec);
    assertEquals(
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        aroundSpec.getAdvice().getMethodType().getDescriptor());
    assertEquals(
        "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)",
        aroundSpec.getSignature());
    assertNotNull(aroundSpec.findThis());
    assertNull(aroundSpec.findReturn());
    assertNull(aroundSpec.findAllArguments());
    assertNull(aroundSpec.findInvokeDynamicConstants());
    List<Integer> arguments = getArguments(aroundSpec);
    assertEquals(Arrays.asList(0, 1), arguments);
  }

  @CallSite(spi = CallSites.class)
  static class AfterAdvice {
    @CallSite.After(
        "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)")
    static String after(
        @CallSite.This String self,
        @CallSite.Argument String regexp,
        @CallSite.Argument String replacement,
        @CallSite.Return String result) {
      return result;
    }
  }

  @Test
  void testSpecificationBuilderForAfterAdvice() {
    File advice = fetchClass(AfterAdvice.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(AfterAdvice.class.getName(), result.getClazz().getClassName());
    AfterSpecification afterSpec = (AfterSpecification) findAdvice(result, "after");
    assertNotNull(afterSpec);
    assertEquals(
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        afterSpec.getAdvice().getMethodType().getDescriptor());
    assertEquals(
        "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)",
        afterSpec.getSignature());
    assertNotNull(afterSpec.findThis());
    assertNotNull(afterSpec.findReturn());
    assertNull(afterSpec.findAllArguments());
    assertNull(afterSpec.findInvokeDynamicConstants());
    List<Integer> arguments = getArguments(afterSpec);
    assertEquals(Arrays.asList(0, 1), arguments);
  }

  @CallSite(spi = CallSites.class)
  static class AllArgsAdvice {
    @CallSite.Around(
        "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)")
    static String allArgs(
        @CallSite.AllArguments(includeThis = true) Object[] arguments,
        @CallSite.Return String result) {
      return result;
    }
  }

  @Test
  void testSpecificationBuilderForAdviceWithAllArguments() {
    File advice = fetchClass(AllArgsAdvice.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(AllArgsAdvice.class.getName(), result.getClazz().getClassName());
    AroundSpecification allArgsSpec = (AroundSpecification) findAdvice(result, "allArgs");
    assertNotNull(allArgsSpec);
    assertEquals(
        "([Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;",
        allArgsSpec.getAdvice().getMethodType().getDescriptor());
    assertEquals(
        "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)",
        allArgsSpec.getSignature());
    assertNull(allArgsSpec.findThis());
    assertNotNull(allArgsSpec.findReturn());
    AllArgsSpecification allArguments = allArgsSpec.findAllArguments();
    assertNotNull(allArguments);
    assertTrue(allArguments.isIncludeThis());
    assertNull(allArgsSpec.findInvokeDynamicConstants());
    List<Integer> arguments = getArguments(allArgsSpec);
    assertEquals(Arrays.asList(), arguments);
  }

  @CallSite(spi = CallSites.class)
  static class InvokeDynamicBeforeAdvice {
    @CallSite.After(
        value =
            "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
        invokeDynamic = true)
    static String invokeDynamic(
        @CallSite.AllArguments Object[] arguments, @CallSite.Return String result) {
      return result;
    }
  }

  @Test
  void testSpecificationBuilderForBeforeInvokeDynamic() {
    File advice = fetchClass(InvokeDynamicBeforeAdvice.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(InvokeDynamicBeforeAdvice.class.getName(), result.getClazz().getClassName());
    AfterSpecification invokeDynamicSpec = (AfterSpecification) findAdvice(result, "invokeDynamic");
    assertNotNull(invokeDynamicSpec);
    assertEquals(
        "([Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;",
        invokeDynamicSpec.getAdvice().getMethodType().getDescriptor());
    assertEquals(
        "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
        invokeDynamicSpec.getSignature());
    assertNull(invokeDynamicSpec.findThis());
    assertNotNull(invokeDynamicSpec.findReturn());
    AllArgsSpecification allArguments = invokeDynamicSpec.findAllArguments();
    assertNotNull(allArguments);
    assertFalse(allArguments.isIncludeThis());
    assertNull(invokeDynamicSpec.findInvokeDynamicConstants());
    List<Integer> arguments = getArguments(invokeDynamicSpec);
    assertEquals(Arrays.asList(), arguments);
  }

  @CallSite(spi = CallSites.class)
  static class InvokeDynamicAroundAdvice {
    @CallSite.Around(
        value =
            "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
        invokeDynamic = true)
    static java.lang.invoke.CallSite invokeDynamic(
        @CallSite.Argument MethodHandles.Lookup lookup,
        @CallSite.Argument String name,
        @CallSite.Argument MethodType concatType,
        @CallSite.Argument String recipe,
        @CallSite.Argument Object... constants) {
      return null;
    }
  }

  @Test
  void testSpecificationBuilderForAroundInvokeDynamic() {
    File advice = fetchClass(InvokeDynamicAroundAdvice.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(InvokeDynamicAroundAdvice.class.getName(), result.getClazz().getClassName());
    AroundSpecification invokeDynamicSpec =
        (AroundSpecification) findAdvice(result, "invokeDynamic");
    assertNotNull(invokeDynamicSpec);
    assertEquals(
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
        invokeDynamicSpec.getAdvice().getMethodType().getDescriptor());
    assertEquals(
        "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
        invokeDynamicSpec.getSignature());
    assertNull(invokeDynamicSpec.findThis());
    assertNull(invokeDynamicSpec.findReturn());
    assertNull(invokeDynamicSpec.findAllArguments());
    assertNull(invokeDynamicSpec.findInvokeDynamicConstants());
    List<Integer> arguments = getArguments(invokeDynamicSpec);
    assertEquals(Arrays.asList(0, 1, 2, 3, 4), arguments);
  }

  @CallSite(spi = CallSites.class)
  static class TestInvokeDynamicConstants {
    @CallSite.After(
        value =
            "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
        invokeDynamic = true)
    static String after(
        @CallSite.AllArguments Object[] parameter,
        @CallSite.InvokeDynamicConstants Object[] constants,
        @CallSite.Return String value) {
      return value;
    }
  }

  @Test
  void testInvokeDynamicConstants() {
    File advice = fetchClass(TestInvokeDynamicConstants.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(TestInvokeDynamicConstants.class.getName(), result.getClazz().getClassName());
    AfterSpecification inheritedSpec = (AfterSpecification) findAdvice(result, "after");
    assertNotNull(inheritedSpec);
    assertEquals(
        "([Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;",
        inheritedSpec.getAdvice().getMethodType().getDescriptor());
    assertEquals(
        "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
        inheritedSpec.getSignature());
    assertNull(inheritedSpec.findThis());
    assertNotNull(inheritedSpec.findReturn());
    assertNotNull(inheritedSpec.findInvokeDynamicConstants());
    List<Integer> arguments = getArguments(inheritedSpec);
    assertEquals(Arrays.asList(), arguments);
  }

  @CallSite(spi = CallSites.class)
  static class TestBeforeArray {

    @CallSite.BeforeArray({
      @CallSite.Before("java.util.Map javax.servlet.ServletRequest.getParameterMap()"),
      @CallSite.Before("java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()")
    })
    static void before(@CallSite.This ServletRequest request) {}
  }

  @Test
  void testSpecificationBuilderForBeforeAdviceArray() {
    File advice = fetchClass(TestBeforeArray.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(TestBeforeArray.class.getName(), result.getClazz().getClassName());
    List<AdviceSpecification> list = result.getAdvices();
    assertEquals(2, list.size());
    for (AdviceSpecification spec : list) {
      assertInstanceOf(BeforeSpecification.class, spec);
      assertEquals(
          "(Ljavax/servlet/ServletRequest;)V", spec.getAdvice().getMethodType().getDescriptor());
      assertTrue(
          spec.getSignature().equals("java.util.Map javax.servlet.ServletRequest.getParameterMap()")
              || spec.getSignature()
                  .equals("java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()"));
      assertNotNull(spec.findThis());
      assertNull(spec.findReturn());
      assertNull(spec.findAllArguments());
      assertNull(spec.findInvokeDynamicConstants());
      List<Integer> arguments = getArguments(spec);
      assertEquals(Arrays.asList(), arguments);
    }
  }

  @CallSite(spi = CallSites.class)
  static class TestAroundArray {

    @CallSite.AroundArray({
      @CallSite.Around("java.util.Map javax.servlet.ServletRequest.getParameterMap()"),
      @CallSite.Around("java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()")
    })
    static Map around(@CallSite.This ServletRequest request) {
      return request.getParameterMap();
    }
  }

  @Test
  void testSpecificationBuilderForAroundAdviceArray() {
    File advice = fetchClass(TestAroundArray.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(TestAroundArray.class.getName(), result.getClazz().getClassName());
    List<AdviceSpecification> list = result.getAdvices();
    assertEquals(2, list.size());
    for (AdviceSpecification spec : list) {
      assertInstanceOf(AroundSpecification.class, spec);
      assertEquals(
          "(Ljavax/servlet/ServletRequest;)Ljava/util/Map;",
          spec.getAdvice().getMethodType().getDescriptor());
      assertTrue(
          spec.getSignature().equals("java.util.Map javax.servlet.ServletRequest.getParameterMap()")
              || spec.getSignature()
                  .equals("java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()"));
      assertNotNull(spec.findThis());
      assertNull(spec.findReturn());
      assertNull(spec.findAllArguments());
      assertNull(spec.findInvokeDynamicConstants());
      List<Integer> arguments = getArguments(spec);
      assertEquals(Arrays.asList(), arguments);
    }
  }

  @CallSite(spi = CallSites.class)
  static class TestAfterArray {

    @CallSite.AfterArray({
      @CallSite.After("java.util.Map javax.servlet.ServletRequest.getParameterMap()"),
      @CallSite.After("java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()")
    })
    static Map after(@CallSite.This ServletRequest request, @CallSite.Return Map parameters) {
      return parameters;
    }
  }

  @Test
  void testSpecificationBuilderForAfterAdviceArray() {
    File advice = fetchClass(TestAfterArray.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(TestAfterArray.class.getName(), result.getClazz().getClassName());
    List<AdviceSpecification> list = result.getAdvices();
    assertEquals(2, list.size());
    for (AdviceSpecification spec : list) {
      assertInstanceOf(AfterSpecification.class, spec);
      assertEquals(
          "(Ljavax/servlet/ServletRequest;Ljava/util/Map;)Ljava/util/Map;",
          spec.getAdvice().getMethodType().getDescriptor());
      assertTrue(
          spec.getSignature().equals("java.util.Map javax.servlet.ServletRequest.getParameterMap()")
              || spec.getSignature()
                  .equals("java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()"));
      assertNotNull(spec.findThis());
      assertNotNull(spec.findReturn());
      assertNull(spec.findAllArguments());
      assertNull(spec.findInvokeDynamicConstants());
      List<Integer> arguments = getArguments(spec);
      assertEquals(Arrays.asList(), arguments);
    }
  }

  @CallSite(spi = CallSites.class)
  static class TestInheritedMethod {
    @CallSite.After(
        "java.lang.String javax.servlet.http.HttpServletRequest.getParameter(java.lang.String)")
    static String after(
        @CallSite.This ServletRequest request,
        @CallSite.Argument String parameter,
        @CallSite.Return String value) {
      return value;
    }
  }

  @Test
  void testSpecificationBuilderForInheritedMethods() {
    File advice = fetchClass(TestInheritedMethod.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(TestInheritedMethod.class.getName(), result.getClazz().getClassName());
    AfterSpecification inheritedSpec = (AfterSpecification) findAdvice(result, "after");
    assertNotNull(inheritedSpec);
    assertEquals(
        "(Ljavax/servlet/ServletRequest;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        inheritedSpec.getAdvice().getMethodType().getDescriptor());
    assertEquals(
        "java.lang.String javax.servlet.http.HttpServletRequest.getParameter(java.lang.String)",
        inheritedSpec.getSignature());
    assertNotNull(inheritedSpec.findThis());
    assertNotNull(inheritedSpec.findReturn());
    assertNull(inheritedSpec.findAllArguments());
    assertNull(inheritedSpec.findInvokeDynamicConstants());
    List<Integer> arguments = getArguments(inheritedSpec);
    assertEquals(Arrays.asList(0), arguments);
  }

  static class IsEnabled {
    static boolean isEnabled(String defaultValue) {
      return true;
    }
  }

  @CallSite(
      spi = CallSites.class,
      enabled = {
        "datadog.trace.plugin.csi.impl.AsmSpecificationBuilderTest$IsEnabled",
        "isEnabled",
        "true"
      })
  static class TestEnablement {
    @CallSite.After(
        "java.lang.String javax.servlet.http.HttpServletRequest.getParameter(java.lang.String)")
    static String after(
        @CallSite.This ServletRequest request,
        @CallSite.Argument String parameter,
        @CallSite.Return String value) {
      return value;
    }
  }

  @Test
  void testSpecificationBuilderWithEnabledProperty() {
    File advice = fetchClass(TestEnablement.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(TestEnablement.class.getName(), result.getClazz().getClassName());
    assertNotNull(result.getEnabled());
    assertEquals(Type.getType(IsEnabled.class), result.getEnabled().getMethod().getOwner());
    assertEquals("isEnabled", result.getEnabled().getMethod().getMethodName());
    assertEquals(
        Type.getMethodType(Types.BOOLEAN, Types.STRING),
        result.getEnabled().getMethod().getMethodType());
    assertEquals(Arrays.asList("true"), result.getEnabled().getArguments());
  }

  @CallSite(spi = CallSites.class)
  static class TestWithOtherAnnotations {
    @CallSite.Around("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.Object)")
    @CallSite.Around("java.lang.StringBuffer java.lang.StringBuffer.append(java.lang.Object)")
    @Nonnull
    @SuppressFBWarnings("NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE")
    static Appendable aroundAppend(
        @CallSite.This @Nullable Appendable self, @CallSite.Argument(0) @Nullable Object param)
        throws Throwable {
      return self.append(param.toString());
    }
  }

  @Test
  void testSpecificationBuilderWithMultipleMethodAnnotations() {
    File advice = fetchClass(TestWithOtherAnnotations.class);
    AsmSpecificationBuilder specificationBuilder = new AsmSpecificationBuilder();

    CallSiteSpecification result =
        specificationBuilder.build(advice).orElseThrow(RuntimeException::new);

    assertEquals(TestWithOtherAnnotations.class.getName(), result.getClazz().getClassName());
    assertEquals(2, result.getAdvices().size());
  }

  private static List<Integer> getArguments(AdviceSpecification advice) {
    return advice.getArguments().map(arg -> arg.getIndex()).collect(Collectors.toList());
  }

  private static AdviceSpecification findAdvice(CallSiteSpecification result, String name) {
    return result.getAdvices().stream()
        .filter(it -> it.getAdvice().getMethodName().equals(name))
        .findFirst()
        .orElse(null);
  }
}
