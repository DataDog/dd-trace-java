package datadog.trace.plugin.csi.impl

import datadog.trace.agent.tooling.csi.CallSite
import datadog.trace.agent.tooling.csi.CallSites
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AfterSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification
import datadog.trace.plugin.csi.util.Types
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import groovy.transform.CompileDynamic
import org.objectweb.asm.Type

import javax.annotation.Nonnull
import javax.annotation.Nullable
import javax.servlet.ServletRequest
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.stream.Collectors

@CompileDynamic
final class AsmSpecificationBuilderTest extends BaseCsiPluginTest {

  static class NonCallSite {}

  void 'test specification builder for non call site'() {
    setup:
    final advice = fetchClass(NonCallSite)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice)

    then:
    !result.present
  }

  @CallSite(spi = Spi)
  static class WithSpiClass {
    interface Spi {}
  }

  void 'test specification builder with custom spi class'() {
    setup:
    final advice = fetchClass(WithSpiClass)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.spi == [Type.getType(WithSpiClass.Spi)] as Type[]
  }

  @CallSite(spi = CallSites, helpers = [SampleHelper1.class, SampleHelper2.class])
  static class HelpersAdvice {
    static class SampleHelper1 {}
    static class SampleHelper2 {}
  }

  void 'test specification builder with custom helper classes'() {
    setup:
    final advice = fetchClass(HelpersAdvice)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.helpers.toList().containsAll([
      Type.getType(HelpersAdvice),
      Type.getType(HelpersAdvice.SampleHelper1),
      Type.getType(HelpersAdvice.SampleHelper2)
    ])
  }

  @CallSite(spi = CallSites)
  static class BeforeAdvice {
    @CallSite.Before('java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)')
    static void before(@CallSite.This final String self, @CallSite.Argument final String regexp, @CallSite.Argument final String replacement) {
    }
  }

  void 'test specification builder for before advice'() {
    setup:
    final advice = fetchClass(BeforeAdvice)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == BeforeAdvice.name
    final beforeSpec = findAdvice(result, 'before')
    beforeSpec instanceof BeforeSpecification
    beforeSpec.advice.methodType.descriptor == '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V'
    beforeSpec.signature == 'java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)'
    beforeSpec.findThis() != null
    beforeSpec.findReturn() == null
    beforeSpec.findAllArguments() == null
    beforeSpec.findInvokeDynamicConstants() == null
    final arguments = getArguments(beforeSpec)
    arguments == [0, 1]
  }

  @CallSite(spi = CallSites)
  static class AroundAdvice {
    @CallSite.Around('java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)')
    static String around(@CallSite.This final String self, @CallSite.Argument final String regexp, @CallSite.Argument final String replacement) {
      return self.replaceAll(regexp, replacement)
    }
  }

  void 'test specification builder for around advice'() {
    setup:
    final advice = fetchClass(AroundAdvice)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == AroundAdvice.name
    final aroundSpec = findAdvice(result, 'around')
    aroundSpec instanceof AroundSpecification
    aroundSpec.advice.methodType.descriptor == '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;'
    aroundSpec.signature == 'java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)'
    aroundSpec.findThis() != null
    aroundSpec.findReturn() == null
    aroundSpec.findAllArguments() == null
    aroundSpec.findInvokeDynamicConstants() == null
    final arguments = getArguments(aroundSpec)
    arguments == [0, 1]
  }

  @CallSite(spi = CallSites)
  static class AfterAdvice {
    @CallSite.After('java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)')
    static String after(@CallSite.This final String self, @CallSite.Argument final String regexp, @CallSite.Argument final String replacement, @CallSite.Return final String result) {
      return result
    }
  }

  void 'test specification builder for after advice'() {
    setup:
    final advice = fetchClass(AfterAdvice)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == AfterAdvice.name
    final afterSpec = findAdvice(result, 'after')
    afterSpec instanceof AfterSpecification
    afterSpec.advice.methodType.descriptor == '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;'
    afterSpec.signature == 'java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)'
    afterSpec.findThis() != null
    afterSpec.findReturn() != null
    afterSpec.findAllArguments() == null
    afterSpec.findInvokeDynamicConstants() == null
    final arguments = getArguments(afterSpec)
    arguments == [0, 1]
  }

  @CallSite
  static class AllArgsAdvice {
    @CallSite.Around('java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)')
    static String allArgs(@CallSite.AllArguments(includeThis = true) final Object[] arguments, @CallSite.Return final String result) {
      return result
    }
  }

  void 'test specification builder for advice with @AllArguments'() {
    setup:
    final advice = fetchClass(AllArgsAdvice)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == AllArgsAdvice.name
    final allArgsSpec = findAdvice(result, 'allArgs')
    allArgsSpec instanceof AroundSpecification
    allArgsSpec.advice.methodType.descriptor == '([Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;'
    allArgsSpec.signature == 'java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)'
    allArgsSpec.findThis() == null
    allArgsSpec.findReturn() != null
    final allArguments = allArgsSpec.findAllArguments()
    allArguments != null
    allArguments.includeThis
    allArgsSpec.findInvokeDynamicConstants() == null
    final arguments = getArguments(allArgsSpec)
    arguments == []
  }

  @CallSite(spi = CallSites)
  static class InvokeDynamicBeforeAdvice {
    @CallSite.After(
    value = 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])',
    invokeDynamic = true
    )
    static String invokeDynamic(@CallSite.AllArguments final Object[] arguments, @CallSite.Return final String result) {
      return result
    }
  }

  void 'test specification builder for before invoke dynamic'() {
    setup:
    final advice = fetchClass(InvokeDynamicBeforeAdvice)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == InvokeDynamicBeforeAdvice.name
    final invokeDynamicSpec = findAdvice(result, 'invokeDynamic')
    invokeDynamicSpec instanceof AfterSpecification
    invokeDynamicSpec.advice.methodType.descriptor == '([Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;'
    invokeDynamicSpec.signature == 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])'
    invokeDynamicSpec.findThis() == null
    invokeDynamicSpec.findReturn() != null
    final allArguments = invokeDynamicSpec.findAllArguments()
    allArguments != null
    !allArguments.includeThis
    invokeDynamicSpec.findInvokeDynamicConstants() == null
    final arguments = getArguments(invokeDynamicSpec)
    arguments == []
  }

  @CallSite(spi = CallSites)
  static class InvokeDynamicAroundAdvice {
    @CallSite.Around(
    value = 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])',
    invokeDynamic = true
    )
    static java.lang.invoke.CallSite invokeDynamic(@CallSite.Argument final MethodHandles.Lookup lookup,
    @CallSite.Argument final String name,
    @CallSite.Argument final MethodType concatType,
    @CallSite.Argument final String recipe,
    @CallSite.Argument final Object... constants) {
      return null
    }
  }

  void 'test specification builder for around invoke dynamic'() {
    setup:
    final advice = fetchClass(InvokeDynamicAroundAdvice)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == InvokeDynamicAroundAdvice.name
    final invokeDynamicSpec = findAdvice(result, 'invokeDynamic')
    invokeDynamicSpec instanceof AroundSpecification
    invokeDynamicSpec.advice.methodType.descriptor == '(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;'
    invokeDynamicSpec.signature == 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])'
    invokeDynamicSpec.findThis() == null
    invokeDynamicSpec.findReturn() == null
    invokeDynamicSpec.findAllArguments() == null
    invokeDynamicSpec.findInvokeDynamicConstants() == null
    final arguments = getArguments(invokeDynamicSpec)
    arguments == [0, 1, 2, 3, 4]
  }

  @CallSite(spi = CallSites)
  static class TestInvokeDynamicConstants {
    @CallSite.After(
    value = 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])',
    invokeDynamic = true
    )
    static String after(@CallSite.AllArguments final Object[] parameter,
    @CallSite.InvokeDynamicConstants final Object[] constants,
    @CallSite.Return final String value) {
      return value
    }
  }

  void 'test invoke dynamic constants'() {
    setup:
    final advice = fetchClass(TestInvokeDynamicConstants)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == TestInvokeDynamicConstants.name
    final inheritedSpec = findAdvice(result, 'after')
    inheritedSpec instanceof AfterSpecification
    inheritedSpec.advice.methodType.descriptor == '([Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;'
    inheritedSpec.signature == 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])'
    inheritedSpec.findThis() == null
    inheritedSpec.findReturn() != null
    inheritedSpec.findInvokeDynamicConstants() != null
    final arguments = getArguments(inheritedSpec)
    arguments == []
  }

  @CallSite(spi = CallSites)
  static class TestBeforeArray {

    @CallSite.BeforeArray([
      @CallSite.Before('java.util.Map javax.servlet.ServletRequest.getParameterMap()'),
      @CallSite.Before('java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()')
    ])
    static void before(@CallSite.This final ServletRequest request) { }
  }

  void 'test specification builder for before advice array'() {
    setup:
    final advice = fetchClass(TestBeforeArray)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == TestBeforeArray.name
    final list = result.advices
    list.size() == 2
    list.each {
      assert it instanceof BeforeSpecification
      assert it.advice.methodType.descriptor == '(Ljavax/servlet/ServletRequest;)V'
      assert it.signature in [
        'java.util.Map javax.servlet.ServletRequest.getParameterMap()',
        'java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()'
      ]
      assert it.findThis() != null
      assert it.findReturn() == null
      assert it.findAllArguments() == null
      assert it.findInvokeDynamicConstants() == null
      final arguments = getArguments(it)
      assert arguments == []
    }
  }

  @CallSite(spi = CallSites)
  static class TestAroundArray {

    @CallSite.AroundArray([
      @CallSite.Around('java.util.Map javax.servlet.ServletRequest.getParameterMap()'),
      @CallSite.Around('java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()')
    ])
    static Map around(@CallSite.This final ServletRequest request) {
      return request.getParameterMap()
    }
  }

  void 'test specification builder for around advice array'() {
    setup:
    final advice = fetchClass(TestAroundArray)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == TestAroundArray.name
    final list = result.advices
    list.size() == 2
    list.each {
      assert it instanceof AroundSpecification
      assert it.advice.methodType.descriptor == '(Ljavax/servlet/ServletRequest;)Ljava/util/Map;'
      assert it.signature in [
        'java.util.Map javax.servlet.ServletRequest.getParameterMap()',
        'java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()'
      ]
      assert it.findThis() != null
      assert it.findReturn() == null
      assert it.findAllArguments() == null
      assert it.findInvokeDynamicConstants() == null
      final arguments = getArguments(it)
      assert arguments == []
    }
  }

  @CallSite(spi = CallSites)
  static class TestAfterArray {

    @CallSite.AfterArray([
      @CallSite.After('java.util.Map javax.servlet.ServletRequest.getParameterMap()'),
      @CallSite.After('java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()')
    ])
    static Map after(@CallSite.This final ServletRequest request, @CallSite.Return final Map parameters) {
      return parameters
    }
  }

  void 'test specification builder for before advice array'() {
    setup:
    final advice = fetchClass(TestAfterArray)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == TestAfterArray.name
    final list = result.advices
    list.size() == 2
    list.each {
      assert it instanceof AfterSpecification
      assert it.advice.methodType.descriptor == '(Ljavax/servlet/ServletRequest;Ljava/util/Map;)Ljava/util/Map;'
      assert it.signature in [
        'java.util.Map javax.servlet.ServletRequest.getParameterMap()',
        'java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()'
      ]
      assert it.findThis() != null
      assert it.findReturn() != null
      assert it.findAllArguments() == null
      assert it.findInvokeDynamicConstants() == null
      final arguments = getArguments(it)
      assert arguments == []
    }
  }

  @CallSite(spi = CallSites)
  static class TestInheritedMethod {
    @CallSite.After('java.lang.String javax.servlet.http.HttpServletRequest.getParameter(java.lang.String)')
    static String after(@CallSite.This final ServletRequest request, @CallSite.Argument final String parameter, @CallSite.Return final String value) {
      return value
    }
  }

  void 'test specification builder for inherited methods'() {
    setup:
    final advice = fetchClass(TestInheritedMethod)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == TestInheritedMethod.name
    final inheritedSpec = findAdvice(result, 'after')
    inheritedSpec instanceof AfterSpecification
    inheritedSpec.advice.methodType.descriptor == '(Ljavax/servlet/ServletRequest;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;'
    inheritedSpec.signature == 'java.lang.String javax.servlet.http.HttpServletRequest.getParameter(java.lang.String)'
    inheritedSpec.findThis() != null
    inheritedSpec.findReturn() != null
    inheritedSpec.findAllArguments() == null
    inheritedSpec.findInvokeDynamicConstants() == null
    final arguments = getArguments(inheritedSpec)
    arguments == [0]
  }

  static class IsEnabled {
    static boolean isEnabled(final String defaultValue) {
      return true
    }
  }

  @CallSite(spi = CallSites, enabled = ['datadog.trace.plugin.csi.impl.AsmSpecificationBuilderTest$IsEnabled', 'isEnabled', 'true'])
  static class TestEnablement {
    @CallSite.After('java.lang.String javax.servlet.http.HttpServletRequest.getParameter(java.lang.String)')
    static String after(@CallSite.This final ServletRequest request, @CallSite.Argument final String parameter, @CallSite.Return final String value) {
      return value
    }
  }

  void 'test specification builder with enabled property'() {
    setup:
    final advice = fetchClass(TestEnablement)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == TestEnablement.name
    result.enabled != null
    result.enabled.method.owner == Type.getType(IsEnabled)
    result.enabled.method.methodName == 'isEnabled'
    result.enabled.method.methodType == Type.getMethodType(Types.BOOLEAN, Types.STRING)
    result.enabled.arguments == ['true']
  }

  @CallSite(spi = CallSites)
  static class TestWithOtherAnnotations {
    @CallSite.Around("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.Object)")
    @CallSite.Around("java.lang.StringBuffer java.lang.StringBuffer.append(java.lang.Object)")
    @Nonnull
    @SuppressFBWarnings(
    "NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE") // we do check for null on self
    // parameter
    static Appendable aroundAppend(@CallSite.This @Nullable final Appendable self, @CallSite.Argument(0) @Nullable final Object param) throws Throwable {
      return self.append(param.toString())
    }
  }

  void 'test specification builder with multiple method annotations'() {
    setup:
    final advice = fetchClass(TestWithOtherAnnotations)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == TestWithOtherAnnotations.name
    result.advices.size() == 2
  }

  private static List<Integer> getArguments(final AdviceSpecification advice) {
    return advice.arguments.map(it -> it.index).collect(Collectors.toList())
  }

  private static AdviceSpecification findAdvice(final CallSiteSpecification result, final String name) {
    return result.advices.find { it.advice.methodName == name }
  }
}
