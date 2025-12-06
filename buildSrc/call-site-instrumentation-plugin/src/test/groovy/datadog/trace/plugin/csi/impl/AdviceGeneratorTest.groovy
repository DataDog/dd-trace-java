package datadog.trace.plugin.csi.impl


import datadog.trace.agent.tooling.csi.CallSite
import datadog.trace.agent.tooling.csi.CallSites
import datadog.trace.plugin.csi.AdviceGenerator
import datadog.trace.plugin.csi.impl.assertion.AssertBuilder
import datadog.trace.plugin.csi.impl.assertion.CallSiteAssert
import datadog.trace.plugin.csi.impl.ext.tests.IastCallSites
import datadog.trace.plugin.csi.impl.ext.tests.RaspCallSites
import groovy.transform.CompileDynamic
import spock.lang.Requires
import spock.lang.TempDir

import javax.servlet.ServletRequest
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

import static CallSiteFactory.pointcutParser

@CompileDynamic
final class AdviceGeneratorTest extends BaseCsiPluginTest {

  @TempDir
  private File buildDir

  @CallSite(spi = CallSites)
  class BeforeAdvice {
    @CallSite.Before('java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)')
    static void before(@CallSite.Argument final String algorithm) {}
  }

  void 'test before advice'() {
    setup:
    final spec = buildClassSpecification(BeforeAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(BeforeAdvice)
      advices(0) {
        type("BEFORE")
        pointcut('java/security/MessageDigest', 'getInstance', '(Ljava/lang/String;)Ljava/security/MessageDigest;')
        statements(
          'handler.dupParameters(descriptor, StackDupMode.COPY);',
          'handler.advice("datadog/trace/plugin/csi/impl/AdviceGeneratorTest$BeforeAdvice", "before", "(Ljava/lang/String;)V");',
          'handler.method(opcode, owner, name, descriptor, isInterface);'
          )
      }
    }
  }

  @CallSite(spi = CallSites)
  class AroundAdvice {
    @CallSite.Around('java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)')
    static String around(@CallSite.This final String self, @CallSite.Argument final String regexp, @CallSite.Argument final String replacement) {
      return self.replaceAll(regexp, replacement)
    }
  }

  void 'test around advice'() {
    setup:
    final spec = buildClassSpecification(AroundAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(AroundAdvice)
      advices(0) {
        type("AROUND")
        pointcut('java/lang/String', 'replaceAll', '(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;')
        statements(
          'handler.advice("datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AroundAdvice", "around", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");'
          )
      }
    }
  }

  @CallSite(spi = CallSites)
  class AfterAdvice {
    @CallSite.After('java.lang.String java.lang.String.concat(java.lang.String)')
    static String after(@CallSite.This final String self, @CallSite.Argument final String param, @CallSite.Return final String result) {
      return result
    }
  }

  void 'test after advice'() {
    setup:
    final spec = buildClassSpecification(AfterAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(AfterAdvice)
      advices(0) {
        type("AFTER")
        pointcut('java/lang/String', 'concat', '(Ljava/lang/String;)Ljava/lang/String;')
        statements(
          'handler.dupInvoke(owner, descriptor, StackDupMode.COPY);',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
          'handler.advice("datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AfterAdvice", "after", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");',
          )
      }
    }
  }

  @CallSite(spi = CallSites)
  class AfterAdviceCtor {
    @CallSite.After('void java.net.URL.<init>(java.lang.String)')
    static URL after(@CallSite.AllArguments final Object[] args, @CallSite.Return final URL url) {
      return url
    }
  }

  void 'test after advice ctor'() {
    setup:
    final spec = buildClassSpecification(AfterAdviceCtor)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(AfterAdviceCtor)
      advices(0) {
        pointcut('java/net/URL', '<init>', '(Ljava/lang/String;)V')
        statements(
          'handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY_CTOR);',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
          'handler.advice("datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AfterAdviceCtor", "after", "([Ljava/lang/Object;Ljava/net/URL;)Ljava/net/URL;");',
          )
      }
    }
  }

  @CallSite(spi = SampleSpi.class)
  class SpiAdvice {
    @CallSite.Before('java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)')
    static void before(@CallSite.Argument final String algorithm) {}

    interface SampleSpi {}
  }

  void 'test generator with spi'() {
    setup:
    final spec = buildClassSpecification(SpiAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)

    assertCallSites(result.file) {
      interfaces(CallSites, SpiAdvice.SampleSpi)
    }
  }

  @CallSite(spi = CallSites)
  class InvokeDynamicAfterAdvice {
    @CallSite.After(
    value = 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])',
    invokeDynamic = true
    )
    static String after(@CallSite.AllArguments final Object[] arguments, @CallSite.Return final String result) {
      result
    }
  }

  @Requires({
    jvm.java9Compatible
  })
  void 'test invoke dynamic after advice'() {
    setup:
    final spec = buildClassSpecification(InvokeDynamicAfterAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(InvokeDynamicAfterAdvice)
      advices(0) {
        pointcut(
          'java/lang/invoke/StringConcatFactory',
          'makeConcatWithConstants',
          '(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;'
          )
        statements(
          'handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY);',
          'handler.invokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);',
          'handler.advice("datadog/trace/plugin/csi/impl/AdviceGeneratorTest$InvokeDynamicAfterAdvice", "after", "([Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;");'
          )
      }
    }
  }

  @CallSite(spi = CallSites)
  class InvokeDynamicAroundAdvice {
    @CallSite.Around(
    value = 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])',
    invokeDynamic = true
    )
    static java.lang.invoke.CallSite around(@CallSite.Argument final MethodHandles.Lookup lookup,
      @CallSite.Argument final String name,
      @CallSite.Argument final MethodType concatType,
      @CallSite.Argument final String recipe,
      @CallSite.Argument final Object... constants) {
      return null
    }
  }

  @Requires({
    jvm.java9Compatible
  })
  void 'test invoke dynamic around advice'() {
    setup:
    final spec = buildClassSpecification(InvokeDynamicAroundAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(InvokeDynamicAroundAdvice)
      advices(0) {
        pointcut(
          'java/lang/invoke/StringConcatFactory',
          'makeConcatWithConstants',
          '(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;'
          )
        statements(
          'handler.invokeDynamic(name, descriptor, new Handle(Opcodes.H_INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$InvokeDynamicAroundAdvice", "around", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false), bootstrapMethodArguments);',
          )
      }
    }
  }

  @CallSite(spi = CallSites)
  class InvokeDynamicWithConstantsAdvice {
    @CallSite.After(
    value = 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])',
    invokeDynamic = true
    )
    static String after(@CallSite.AllArguments final Object[] arguments,
      @CallSite.Return final String result,
      @CallSite.InvokeDynamicConstants final Object[] constants) {
      return result
    }
  }

  @Requires({
    jvm.java9Compatible
  })
  void 'test invoke dynamic with constants advice'() {
    setup:
    final spec = buildClassSpecification(InvokeDynamicWithConstantsAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(InvokeDynamicWithConstantsAdvice)
      advices(0) {
        pointcut(
          'java/lang/invoke/StringConcatFactory',
          'makeConcatWithConstants',
          '(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;'
          )
        statements(
          'handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY);',
          'handler.invokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);',
          'handler.loadConstantArray(bootstrapMethodArguments);',
          'handler.advice("datadog/trace/plugin/csi/impl/AdviceGeneratorTest$InvokeDynamicWithConstantsAdvice", "after", "([Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;");'
          )
      }
    }
  }

  @CallSite(spi = CallSites)
  class ArrayAdvice {
    @CallSite.AfterArray([
      @CallSite.After('java.util.Map javax.servlet.ServletRequest.getParameterMap()'),
      @CallSite.After('java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()')
    ])
    static Map after(@CallSite.This final ServletRequest request, @CallSite.Return final Map parameters) {
      return parameters
    }
  }

  void 'test array advice'() {
    setup:
    final spec = buildClassSpecification(ArrayAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    assertCallSites(result.file) {
      advices(0) {
        pointcut('javax/servlet/ServletRequest', 'getParameterMap', '()Ljava/util/Map;')
      }
      advices(1) {
        pointcut('javax/servlet/ServletRequestWrapper', 'getParameterMap', '()Ljava/util/Map;')
      }
    }
  }

  class MinJavaVersionCheck {
    static boolean isAtLeast(final String version) {
      return Integer.parseInt(version) >= 9
    }
  }

  @CallSite(spi = CallSites, enabled = ['datadog.trace.plugin.csi.impl.AdviceGeneratorTest$MinJavaVersionCheck', 'isAtLeast', '18'])
  class MinJavaVersionAdvice {
    @CallSite.After('java.lang.String java.lang.String.concat(java.lang.String)')
    static String after(@CallSite.This final String self, @CallSite.Argument final String param, @CallSite.Return final String result) {
      return result
    }
  }

  void 'test custom enabled property'() {
    setup:
    final spec = buildClassSpecification(MinJavaVersionAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    assertCallSites(result.file) { callSites ->
      interfaces(CallSites, CallSites.HasEnabledProperty)
      enabled(MinJavaVersionCheck.getDeclaredMethod('isAtLeast', String), '18')
    }
  }


  @CallSite(spi = CallSites)
  class PartialArgumentsBeforeAdvice {
    @CallSite.Before("int java.sql.Statement.executeUpdate(java.lang.String, java.lang.String[])")
    static void before(@CallSite.Argument(0) String arg1) {}

    @CallSite.Before("java.lang.String java.lang.String.format(java.lang.String, java.lang.Object[])")
    static void before(@CallSite.Argument(1) Object[] arg) {}

    @CallSite.Before("java.lang.CharSequence java.lang.String.subSequence(int, int)")
    static void before(@CallSite.This String thiz, @CallSite.Argument(0) int arg) {}
  }

  void 'partial arguments with before advice'() {
    setup:
    final spec = buildClassSpecification(PartialArgumentsBeforeAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors result
    assertCallSites(result.file) {
      advices(0) {
        pointcut('java/sql/Statement', 'executeUpdate', '(Ljava/lang/String;[Ljava/lang/String;)I')
        statements(
          'int[] parameterIndices = new int[] { 0 };',
          'handler.dupParameters(descriptor, parameterIndices, owner);',
          'handler.advice("datadog/trace/plugin/csi/impl/AdviceGeneratorTest$PartialArgumentsBeforeAdvice", "before", "(Ljava/lang/String;)V");',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
          )
      }
      advices(1) {
        pointcut('java/lang/String', 'format', '(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;')
        statements(
          'int[] parameterIndices = new int[] { 1 };',
          'handler.dupParameters(descriptor, parameterIndices, null);',
          'handler.advice("datadog/trace/plugin/csi/impl/AdviceGeneratorTest$PartialArgumentsBeforeAdvice", "before", "([Ljava/lang/Object;)V");',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
          )
      }
      advices(2) {
        pointcut('java/lang/String', 'subSequence', '(II)Ljava/lang/CharSequence;')
        statements(
          'int[] parameterIndices = new int[] { 0 };',
          'handler.dupInvoke(owner, descriptor, parameterIndices);',
          'handler.advice("datadog/trace/plugin/csi/impl/AdviceGeneratorTest$PartialArgumentsBeforeAdvice", "before", "(Ljava/lang/String;I)V");',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
          )
      }
    }
  }


  @CallSite(spi = CallSites)
  class SuperTypeReturnAdvice {
    @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.String)")
    static Object after(@CallSite.AllArguments Object[] args, @CallSite.Return Object result) {
      return result
    }
  }

  void 'test returning super type'() {
    setup:
    final spec = buildClassSpecification(SuperTypeReturnAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors result
    assertCallSites(result.file) {
      advices(0) {
        pointcut('java/lang/StringBuilder', '<init>', '(Ljava/lang/String;)V')
        statements(
          'handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY_CTOR);',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
          'handler.advice("datadog/trace/plugin/csi/impl/AdviceGeneratorTest$SuperTypeReturnAdvice", "after", "([Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");',
          'handler.instruction(Opcodes.CHECKCAST, "java/lang/StringBuilder");'
          )
      }
    }
  }

  @CallSite(spi = [IastCallSites, RaspCallSites])
  class MultipleSpiClassesAdvice {
    @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.String)")
    static Object after(@CallSite.AllArguments Object[] args, @CallSite.Return Object result) {
      return result
    }
  }

  void 'test multiple spi classes'() {
    setup:
    final spec = buildClassSpecification(MultipleSpiClassesAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors result
    assertCallSites(result.file) {
      spi(IastCallSites, RaspCallSites)
    }
  }


  @CallSite(spi = CallSites)
  class AfterAdviceWithVoidReturn {
    @CallSite.After("void java.lang.StringBuilder.setLength(int)")
    static void after(@CallSite.This StringBuilder self, @CallSite.Argument(0) int length) {
    }
  }

  void 'test after advice with void return'() {
    setup:
    final spec = buildClassSpecification(AfterAdviceWithVoidReturn)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors result
    assertCallSites(result.file) {
      advices(0) {
        pointcut('java/lang/StringBuilder', 'setLength', '(I)V')
        statements(
          'handler.dupInvoke(owner, descriptor, StackDupMode.COPY);',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
          'handler.advice("datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AfterAdviceWithVoidReturn", "after", "(Ljava/lang/StringBuilder;I)V");',
          )
      }
    }
  }

  private static AdviceGenerator buildAdviceGenerator(final File targetFolder) {
    return new AdviceGeneratorImpl(targetFolder, pointcutParser())
  }

  private static void assertCallSites(final File generated, @DelegatesTo(CallSiteAssert) final Closure closure) {
    final asserter = new AssertBuilder(generated).build()
    closure.delegate = asserter
    closure(asserter)
  }
}
