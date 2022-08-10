package datadog.trace.plugin.csi.impl

import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AfterSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification
import datadog.trace.plugin.csi.ValidationContext
import datadog.trace.plugin.csi.samples.AfterAdvice
import datadog.trace.plugin.csi.samples.AroundAdvice
import datadog.trace.plugin.csi.samples.BeforeAdvice
import datadog.trace.plugin.csi.samples.HelpersAdvice
import datadog.trace.plugin.csi.samples.SpiAdvice
import org.objectweb.asm.Type
import spock.lang.Specification

import java.nio.file.Paths
import java.util.stream.Collectors

final class AsmSpecificationBuilderTest extends Specification {

  def 'test specification builder for non call site'() {
    setup:
    final advice = fetchClass(ValidationContext)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice)

    then:
    !result.present
  }

  def 'test spi interface'() {
    setup:
    final advice = fetchClass(SpiAdvice)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.spi == Type.getType(SpiAdvice.SampleSpi)
  }

  def 'test helper classes'() {
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

  def 'test specification builder for before advice'() {
    setup:
    final advice = fetchClass(BeforeAdvice)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == BeforeAdvice.name
    final beforeSpec = result.advices.filter { it.advice.methodName == 'beforeMessageDigestGetInstance' }.findFirst().get()
    beforeSpec instanceof BeforeSpecification
    beforeSpec.advice.methodType.descriptor == '(Ljava/lang/String;)V'
    beforeSpec.signature == 'java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)'
    !beforeSpec.hasThis()
    !beforeSpec.hasReturn()
    final arguments = getArguments(beforeSpec)
    arguments == [0]
  }

  def 'test specification builder for around advice'() {
    setup:
    final advice = fetchClass(AroundAdvice)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == AroundAdvice.name
    final aroundSpec = result.advices.filter { it.advice.methodName == 'aroundStringReplaceAll' }.findFirst().get()
    aroundSpec instanceof AroundSpecification
    aroundSpec.advice.methodType.descriptor == '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;'
    aroundSpec.signature == 'java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)'
    aroundSpec.hasThis()
    !aroundSpec.hasReturn()
    final arguments = getArguments(aroundSpec)
    arguments == [0, 1]
  }

  def 'test specification builder for after advice'() {
    setup:
    final advice = fetchClass(AfterAdvice)
    final specificationBuilder = new AsmSpecificationBuilder()

    when:
    final result = specificationBuilder.build(advice).orElseThrow(RuntimeException::new)

    then:
    result.clazz.className == AfterAdvice.name
    final afterSpec = result.advices.filter { it.advice.methodName == 'afterUrlConstructor' }.findFirst().get()
    afterSpec instanceof AfterSpecification
    afterSpec.advice.methodType.descriptor == '(Ljava/net/URL;Ljava/lang/String;)Ljava/net/URL;'
    afterSpec.signature == 'void java.net.URL.<init>(java.lang.String)'
    afterSpec.hasThis()
    !afterSpec.hasReturn()
    final arguments = getArguments(afterSpec)
    arguments == [0]
  }

  private File fetchClass(final Class<?> clazz) {
    return Paths.get(clazz.getResource("${clazz.simpleName}.class").toURI()).toFile()
  }

  private List<Integer> getArguments(final AdviceSpecification advice) {
    return advice.arguments.map(it -> it.index).collect(Collectors.toList())
  }
}
