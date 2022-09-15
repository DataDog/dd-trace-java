package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.Advices
import net.bytebuddy.description.type.TypeDescription
import spock.lang.Requires

@Requires({
  jvm.java9Compatible
})
class AdvicesInvokeDynamicTest extends BaseCallSiteTest {

  def 'test constant pool introspector with invoke dynamic'() {
    setup:
    final target = StringPlusExample
    final targetType = Mock(TypeDescription) {
      getName() >> target.name
    }
    final advice = mockInvokeDynamicAdvice(pointcutMock)
    final advices = Advices.fromCallSites(advice)
    final introspector = Advices.AdviceIntrospector.ConstantPoolInstrospector.INSTANCE

    when: 'find is called without a class loader'
    def result = introspector.findAdvices(advices, targetType, null)

    then: 'no filtering should happen'
    result == advices

    when: 'find is called with a class loader'
    result = introspector.findAdvices(advices, targetType, StringPlusExample.classLoader)
    final found = result.findAdvice(pointcutMock) != null

    then: 'constant pool should be used to filter'
    result != advices
    result.empty == emptyAdvices
    found == adviceFound

    where:
    pointcutMock                  | emptyAdvices | adviceFound
    stringConcatPointcut()        | true         | false
    stringConcatFactoryPointcut() | false        | true
  }

  def 'test constant pool introspector with invoke dynamic and constants'() {
    setup:
    final target = StringPlusConstantsExample
    final targetType = Mock(TypeDescription) {
      getName() >> target.name
    }
    final advice = mockInvokeDynamicAdvice(pointcutMock)
    final advices = Advices.fromCallSites(advice)
    final introspector = Advices.AdviceIntrospector.ConstantPoolInstrospector.INSTANCE

    when: 'find is called without a class loader'
    def result = introspector.findAdvices(advices, targetType, null)

    then: 'no filtering should happen'
    result == advices

    when: 'find is called with a class loader'
    result = introspector.findAdvices(advices, targetType, StringPlusConstantsExample.classLoader)
    final found = result.findAdvice(pointcutMock) != null

    then: 'constant pool should be used to filter'
    result != advices
    result.empty == emptyAdvices
    found == adviceFound

    where:
    pointcutMock                  | emptyAdvices | adviceFound
    stringConcatPointcut()        | true         | false
    stringConcatFactoryPointcut() | false        | true
  }
}
