package datadog.trace.plugin.csi.impl;

import static datadog.trace.plugin.csi.impl.CallSiteFactory.pointcutParser;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.plugin.csi.AdviceGenerator;
import datadog.trace.plugin.csi.AdviceGenerator.CallSiteResult;
import datadog.trace.plugin.csi.impl.assertion.AssertBuilder;
import datadog.trace.plugin.csi.impl.assertion.CallSiteAssert;
import datadog.trace.plugin.csi.impl.ext.tests.IastCallSites;
import datadog.trace.plugin.csi.impl.ext.tests.RaspCallSites;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.util.Map;
import javax.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

class AdviceGeneratorTest extends BaseCsiPluginTest {

  @TempDir private File buildDir;

  @CallSite(spi = CallSites.class)
  public static class BeforeAdvice {
    @CallSite.Before(
        "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)")
    public static void before(@CallSite.Argument String algorithm) {}
  }

  @Test
  void testBeforeAdvice() {
    CallSiteSpecification spec = buildClassSpecification(BeforeAdvice.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.interfaces(CallSites.class);
    asserter.helpers(BeforeAdvice.class);
    asserter.advices(
        0,
        advice -> {
          advice.type("BEFORE");
          advice.pointcut(
              "java/security/MessageDigest",
              "getInstance",
              "(Ljava/lang/String;)Ljava/security/MessageDigest;");
          advice.statements(
              "handler.dupParameters(descriptor, StackDupMode.COPY);",
              "handler.advice(\"datadog/trace/plugin/csi/impl/AdviceGeneratorTest$BeforeAdvice\", \"before\", \"(Ljava/lang/String;)V\");",
              "handler.method(opcode, owner, name, descriptor, isInterface);");
        });
  }

  @CallSite(spi = CallSites.class)
  public static class AroundAdvice {
    @CallSite.Around(
        "java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)")
    public static String around(
        @CallSite.This String self,
        @CallSite.Argument String regexp,
        @CallSite.Argument String replacement) {
      return self.replaceAll(regexp, replacement);
    }
  }

  @Test
  void testAroundAdvice() {
    CallSiteSpecification spec = buildClassSpecification(AroundAdvice.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.interfaces(CallSites.class);
    asserter.helpers(AroundAdvice.class);
    asserter.advices(
        0,
        advice -> {
          advice.type("AROUND");
          advice.pointcut(
              "java/lang/String",
              "replaceAll",
              "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
          advice.statements(
              "handler.advice(\"datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AroundAdvice\", \"around\", \"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\");");
        });
  }

  @CallSite(spi = CallSites.class)
  public static class AfterAdvice {
    @CallSite.After("java.lang.String java.lang.String.concat(java.lang.String)")
    public static String after(
        @CallSite.This String self,
        @CallSite.Argument String param,
        @CallSite.Return String result) {
      return result;
    }
  }

  @Test
  void testAfterAdvice() {
    CallSiteSpecification spec = buildClassSpecification(AfterAdvice.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.interfaces(CallSites.class);
    asserter.helpers(AfterAdvice.class);
    asserter.advices(
        0,
        advice -> {
          advice.type("AFTER");
          advice.pointcut("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
          advice.statements(
              "handler.dupInvoke(owner, descriptor, StackDupMode.COPY);",
              "handler.method(opcode, owner, name, descriptor, isInterface);",
              "handler.advice(\"datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AfterAdvice\", \"after\", \"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\");");
        });
  }

  @CallSite(spi = CallSites.class)
  public static class AfterAdviceCtor {
    @CallSite.After("void java.net.URL.<init>(java.lang.String)")
    public static URL after(@CallSite.AllArguments Object[] args, @CallSite.Return URL url) {
      return url;
    }
  }

  @Test
  void testAfterAdviceCtor() {
    CallSiteSpecification spec = buildClassSpecification(AfterAdviceCtor.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.interfaces(CallSites.class);
    asserter.helpers(AfterAdviceCtor.class);
    asserter.advices(
        0,
        advice -> {
          advice.pointcut("java/net/URL", "<init>", "(Ljava/lang/String;)V");
          advice.statements(
              "handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY_CTOR);",
              "handler.method(opcode, owner, name, descriptor, isInterface);",
              "handler.advice(\"datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AfterAdviceCtor\", \"after\", \"([Ljava/lang/Object;Ljava/net/URL;)Ljava/net/URL;\");");
        });
  }

  @CallSite(spi = SpiAdvice.SampleSpi.class)
  public static class SpiAdvice {
    @CallSite.Before(
        "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)")
    public static void before(@CallSite.Argument String algorithm) {}

    interface SampleSpi {}
  }

  @Test
  void testGeneratorWithSpi() {
    CallSiteSpecification spec = buildClassSpecification(SpiAdvice.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.interfaces(CallSites.class, SpiAdvice.SampleSpi.class);
  }

  @CallSite(spi = CallSites.class)
  public static class InvokeDynamicAfterAdvice {
    @CallSite.After(
        value =
            "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
        invokeDynamic = true)
    public static String after(
        @CallSite.AllArguments Object[] arguments, @CallSite.Return String result) {
      return result;
    }
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_9)
  void testInvokeDynamicAfterAdvice() {
    CallSiteSpecification spec = buildClassSpecification(InvokeDynamicAfterAdvice.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.interfaces(CallSites.class);
    asserter.helpers(InvokeDynamicAfterAdvice.class);
    asserter.advices(
        0,
        advice -> {
          advice.pointcut(
              "java/lang/invoke/StringConcatFactory",
              "makeConcatWithConstants",
              "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;");
          advice.statements(
              "handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY);",
              "handler.invokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);",
              "handler.advice(\"datadog/trace/plugin/csi/impl/AdviceGeneratorTest$InvokeDynamicAfterAdvice\", \"after\", \"([Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;\");");
        });
  }

  @CallSite(spi = CallSites.class)
  public static class InvokeDynamicAroundAdvice {
    @CallSite.Around(
        value =
            "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
        invokeDynamic = true)
    public static java.lang.invoke.CallSite around(
        @CallSite.Argument MethodHandles.Lookup lookup,
        @CallSite.Argument String name,
        @CallSite.Argument MethodType concatType,
        @CallSite.Argument String recipe,
        @CallSite.Argument Object... constants) {
      return null;
    }
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_9)
  void testInvokeDynamicAroundAdvice() {
    CallSiteSpecification spec = buildClassSpecification(InvokeDynamicAroundAdvice.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.interfaces(CallSites.class);
    asserter.helpers(InvokeDynamicAroundAdvice.class);
    asserter.advices(
        0,
        advice -> {
          advice.pointcut(
              "java/lang/invoke/StringConcatFactory",
              "makeConcatWithConstants",
              "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;");
          advice.statements(
              "handler.invokeDynamic(name, descriptor, new Handle(Opcodes.H_INVOKESTATIC, \"datadog/trace/plugin/csi/impl/AdviceGeneratorTest$InvokeDynamicAroundAdvice\", \"around\", \"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;\", false), bootstrapMethodArguments);");
        });
  }

  @CallSite(spi = CallSites.class)
  public static class InvokeDynamicWithConstantsAdvice {
    @CallSite.After(
        value =
            "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
        invokeDynamic = true)
    public static String after(
        @CallSite.AllArguments Object[] arguments,
        @CallSite.Return String result,
        @CallSite.InvokeDynamicConstants Object[] constants) {
      return result;
    }
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_9)
  void testInvokeDynamicWithConstantsAdvice() {
    CallSiteSpecification spec = buildClassSpecification(InvokeDynamicWithConstantsAdvice.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.interfaces(CallSites.class);
    asserter.helpers(InvokeDynamicWithConstantsAdvice.class);
    asserter.advices(
        0,
        advice -> {
          advice.pointcut(
              "java/lang/invoke/StringConcatFactory",
              "makeConcatWithConstants",
              "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;");
          advice.statements(
              "handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY);",
              "handler.invokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);",
              "handler.loadConstantArray(bootstrapMethodArguments);",
              "handler.advice(\"datadog/trace/plugin/csi/impl/AdviceGeneratorTest$InvokeDynamicWithConstantsAdvice\", \"after\", \"([Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;\");");
        });
  }

  @CallSite(spi = CallSites.class)
  public static class ArrayAdvice {
    @CallSite.AfterArray({
      @CallSite.After("java.util.Map javax.servlet.ServletRequest.getParameterMap()"),
      @CallSite.After("java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()")
    })
    public static Map after(
        @CallSite.This ServletRequest request, @CallSite.Return Map parameters) {
      return parameters;
    }
  }

  @Test
  void testArrayAdvice() {
    CallSiteSpecification spec = buildClassSpecification(ArrayAdvice.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.advices(
        0,
        advice -> {
          advice.pointcut("javax/servlet/ServletRequest", "getParameterMap", "()Ljava/util/Map;");
        });
    asserter.advices(
        1,
        advice -> {
          advice.pointcut(
              "javax/servlet/ServletRequestWrapper", "getParameterMap", "()Ljava/util/Map;");
        });
  }

  public static class MinJavaVersionCheck {
    public static boolean isAtLeast(String version) {
      return Integer.parseInt(version) >= 9;
    }
  }

  @CallSite(
      spi = CallSites.class,
      enabled = {
        "datadog.trace.plugin.csi.impl.AdviceGeneratorTest$MinJavaVersionCheck",
        "isAtLeast",
        "18"
      })
  public static class MinJavaVersionAdvice {
    @CallSite.After("java.lang.String java.lang.String.concat(java.lang.String)")
    public static String after(
        @CallSite.This String self,
        @CallSite.Argument String param,
        @CallSite.Return String result) {
      return result;
    }
  }

  @Test
  void testCustomEnabledProperty() throws Exception {
    CallSiteSpecification spec = buildClassSpecification(MinJavaVersionAdvice.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.interfaces(CallSites.class, CallSites.HasEnabledProperty.class);
    asserter.enabled(MinJavaVersionCheck.class.getDeclaredMethod("isAtLeast", String.class), "18");
  }

  @CallSite(spi = CallSites.class)
  public static class PartialArgumentsBeforeAdvice {
    @CallSite.Before("int java.sql.Statement.executeUpdate(java.lang.String, java.lang.String[])")
    public static void before(@CallSite.Argument(0) String arg1) {}

    @CallSite.Before(
        "java.lang.String java.lang.String.format(java.lang.String, java.lang.Object[])")
    public static void before(@CallSite.Argument(1) Object[] arg) {}

    @CallSite.Before("java.lang.CharSequence java.lang.String.subSequence(int, int)")
    public static void before(@CallSite.This String thiz, @CallSite.Argument(0) int arg) {}
  }

  @Test
  void partialArgumentsWithBeforeAdvice() {
    CallSiteSpecification spec = buildClassSpecification(PartialArgumentsBeforeAdvice.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.advices(
        0,
        advice -> {
          advice.pointcut(
              "java/sql/Statement", "executeUpdate", "(Ljava/lang/String;[Ljava/lang/String;)I");
          advice.statements(
              "int[] parameterIndices = new int[] { 0 };",
              "handler.dupParameters(descriptor, parameterIndices, owner);",
              "handler.advice(\"datadog/trace/plugin/csi/impl/AdviceGeneratorTest$PartialArgumentsBeforeAdvice\", \"before\", \"(Ljava/lang/String;)V\");",
              "handler.method(opcode, owner, name, descriptor, isInterface);");
        });
    asserter.advices(
        1,
        advice -> {
          advice.pointcut(
              "java/lang/String",
              "format",
              "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;");
          advice.statements(
              "int[] parameterIndices = new int[] { 1 };",
              "handler.dupParameters(descriptor, parameterIndices, null);",
              "handler.advice(\"datadog/trace/plugin/csi/impl/AdviceGeneratorTest$PartialArgumentsBeforeAdvice\", \"before\", \"([Ljava/lang/Object;)V\");",
              "handler.method(opcode, owner, name, descriptor, isInterface);");
        });
    asserter.advices(
        2,
        advice -> {
          advice.pointcut("java/lang/String", "subSequence", "(II)Ljava/lang/CharSequence;");
          advice.statements(
              "int[] parameterIndices = new int[] { 0 };",
              "handler.dupInvoke(owner, descriptor, parameterIndices);",
              "handler.advice(\"datadog/trace/plugin/csi/impl/AdviceGeneratorTest$PartialArgumentsBeforeAdvice\", \"before\", \"(Ljava/lang/String;I)V\");",
              "handler.method(opcode, owner, name, descriptor, isInterface);");
        });
  }

  @CallSite(spi = CallSites.class)
  public static class SuperTypeReturnAdvice {
    @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.String)")
    public static Object after(
        @CallSite.AllArguments Object[] args, @CallSite.Return Object result) {
      return result;
    }
  }

  @Test
  void testReturningSuperType() {
    CallSiteSpecification spec = buildClassSpecification(SuperTypeReturnAdvice.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.advices(
        0,
        advice -> {
          advice.pointcut("java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V");
          advice.statements(
              "handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY_CTOR);",
              "handler.method(opcode, owner, name, descriptor, isInterface);",
              "handler.advice(\"datadog/trace/plugin/csi/impl/AdviceGeneratorTest$SuperTypeReturnAdvice\", \"after\", \"([Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;\");",
              "handler.instruction(Opcodes.CHECKCAST, \"java/lang/StringBuilder\");");
        });
  }

  @CallSite(spi = {IastCallSites.class, RaspCallSites.class})
  public static class MultipleSpiClassesAdvice {
    @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.String)")
    public static Object after(
        @CallSite.AllArguments Object[] args, @CallSite.Return Object result) {
      return result;
    }
  }

  @Test
  void testMultipleSpiClasses() {
    CallSiteSpecification spec = buildClassSpecification(MultipleSpiClassesAdvice.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.spi(IastCallSites.class, RaspCallSites.class);
  }

  @CallSite(spi = CallSites.class)
  public static class AfterAdviceWithVoidReturn {
    @CallSite.After("void java.lang.StringBuilder.setLength(int)")
    public static void after(@CallSite.This StringBuilder self, @CallSite.Argument(0) int length) {}
  }

  @Test
  void testAfterAdviceWithVoidReturn() {
    CallSiteSpecification spec = buildClassSpecification(AfterAdviceWithVoidReturn.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);

    CallSiteResult result = generator.generate(spec);

    assertNoErrors(result);
    CallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.advices(
        0,
        advice -> {
          advice.pointcut("java/lang/StringBuilder", "setLength", "(I)V");
          advice.statements(
              "handler.dupInvoke(owner, descriptor, StackDupMode.COPY);",
              "handler.method(opcode, owner, name, descriptor, isInterface);",
              "handler.advice(\"datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AfterAdviceWithVoidReturn\", \"after\", \"(Ljava/lang/StringBuilder;I)V\");");
        });
  }

  private static AdviceGenerator buildAdviceGenerator(File targetFolder) {
    return new AdviceGeneratorImpl(targetFolder, pointcutParser());
  }

  private static CallSiteAssert assertCallSites(File generated) {
    return new AssertBuilder(generated).build();
  }
}
