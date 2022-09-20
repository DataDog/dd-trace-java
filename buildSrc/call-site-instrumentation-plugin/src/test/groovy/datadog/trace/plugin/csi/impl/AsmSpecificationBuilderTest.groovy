package datadog.trace.plugin.csi.impl

import datadog.trace.agent.tooling.csi.CallSite
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AfterSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification
import org.objectweb.asm.Type

import javax.servlet.ServletRequest
import java.util.stream.Collectors

final class AsmSpecificationBuilderTest extends BaseCsiPluginTest {

  static class NonCallSite {}

  def 'test specification builder for non call site'() {
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

  def 'test specification builder with custom spi class'() {
    setup:
    final advice = fetchClass(WithSpiClass)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.spi == Type.getType(WithSpiClass.Spi)
  }

  @CallSite(helpers = [SampleHelper1.class, SampleHelper2.class])
  static class HelpersAdvice {
    static class SampleHelper1 {}
    static class SampleHelper2 {}
  }

  def 'test specification builder with custom helper classes'() {
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

  @CallSite
  static class BeforeAdvice {
    @CallSite.Before('java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)')
    static void before(@CallSite.This final String self, @CallSite.Argument final String regexp, @CallSite.Argument final String replacement) {}
  }

  def 'test specification builder for before advice'() {
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
    final arguments = getArguments(beforeSpec)
    arguments == [0, 1]
  }

  @CallSite
  static class AroundAdvice {
    @CallSite.Around('java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)')
    static String around(@CallSite.This final String self, @CallSite.Argument final String regexp, @CallSite.Argument final String replacement) {
      return self.replaceAll(regexp, replacement)
    }
  }

  def 'test specification builder for around advice'() {
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
    final arguments = getArguments(aroundSpec)
    arguments == [0, 1]
  }

  @CallSite
  static class AfterAdvice {
    @CallSite.After('java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)')
    static String after(@CallSite.This final String self, @CallSite.Argument final String regexp, @CallSite.Argument final String replacement, @CallSite.Return final String result) {
      return result
    }
  }

  def 'test specification builder for after advice'() {
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

  def 'test specification builder for advice with @AllArguments'() {
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
    final arguments = getArguments(allArgsSpec)
    arguments == []
  }

  @CallSite
  static class InvokeDynamicAdvice {
    @CallSite.After('java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])')
    static String invokeDynamic(@CallSite.AllArguments final Object[] arguments, @CallSite.Return final String result) {
      return result
    }
  }

  def 'test specification builder for invoke dynamic'() {
    setup:
    final advice = fetchClass(InvokeDynamicAdvice)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == InvokeDynamicAdvice.name
    final invokeDynamicSpec = findAdvice(result, 'invokeDynamic')
    invokeDynamicSpec instanceof AfterSpecification
    invokeDynamicSpec.advice.methodType.descriptor == '([Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;'
    invokeDynamicSpec.signature == 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])'
    invokeDynamicSpec.findThis() == null
    invokeDynamicSpec.findReturn() != null
    final allArguments = invokeDynamicSpec.findAllArguments()
    allArguments != null
    !allArguments.includeThis
    final arguments = getArguments(invokeDynamicSpec)
    arguments == []
  }

  @CallSite
  static class TestBeforeArray {

    @CallSite.BeforeArray([
      @CallSite.Before('java.util.Map javax.servlet.ServletRequest.getParameterMap()'),
      @CallSite.Before('java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()')
    ])
    static void before(@CallSite.This final ServletRequest request) { }
  }

  def 'test specification builder for before advice array'() {
    setup:
    final advice = fetchClass(TestBeforeArray)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == TestBeforeArray.name
    final list = result.advices.collect(Collectors.toList())
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
      final arguments = getArguments(it)
      assert arguments == []
    }
  }

  @CallSite
  static class TestAroundArray {

    @CallSite.AroundArray([
      @CallSite.Around('java.util.Map javax.servlet.ServletRequest.getParameterMap()'),
      @CallSite.Around('java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()')
    ])
    static Map around(@CallSite.This final ServletRequest request) {
      return request.getParameterMap()
    }
  }

  def 'test specification builder for before advice array'() {
    setup:
    final advice = fetchClass(TestAroundArray)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == TestAroundArray.name
    final list = result.advices.collect(Collectors.toList())
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
      final arguments = getArguments(it)
      assert arguments == []
    }
  }

  @CallSite
  static class TestAfterArray {

    @CallSite.AfterArray([
      @CallSite.After('java.util.Map javax.servlet.ServletRequest.getParameterMap()'),
      @CallSite.After('java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()')
    ])
    static Map after(@CallSite.This final ServletRequest request, @CallSite.Return final Map parameters) {
      return parameters
    }
  }

  def 'test specification builder for before advice array'() {
    setup:
    final advice = fetchClass(TestAfterArray)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == TestAfterArray.name
    final list = result.advices.collect(Collectors.toList())
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
      final arguments = getArguments(it)
      assert arguments == []
    }
  }

  @CallSite
  static class TestInheritedMethod {
    @CallSite.After('java.lang.String javax.servlet.http.HttpServletRequest.getParameter(java.lang.String)')
    static String after(@CallSite.This final ServletRequest request, @CallSite.Argument final String parameter, @CallSite.Return final String value) {
      return value
    }
  }

  def 'test specification builder for inherited methods'() {
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
    final arguments = getArguments(inheritedSpec)
    arguments == [0]
  }

  protected static List<Integer> getArguments(final AdviceSpecification advice) {
    return advice.arguments.map(it -> it.index).collect(Collectors.toList())
  }

  private static AdviceSpecification findAdvice(final CallSiteSpecification result, final String name) {
    return result.advices.filter { it.advice.methodName == name }.findFirst().get()
  }
}
