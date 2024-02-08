package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.Advices
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import spock.lang.Requires

@Requires({
  jvm.java9Compatible
})
class AdvicesInvokeDynamicTest extends BaseCallSiteTest {

  void 'test constant pool introspector with invoke dynamic'() {
    setup:
    final type = new TypeDescription.ForLoadedType(StringPlusExample)
    final advice = Stub(InvokeDynamicAdvice)
    final advices = Advices.fromCallSites([mockCallSites(advice, pointcutMock)])
    final introspector = Advices.AdviceIntrospector.ConstantPoolInstrospector.INSTANCE

    when:
    final result = introspector.findAdvices(advices, Stub(DynamicType.Builder), type, StringPlusExample.classLoader)
    final found = result.findAdvice(pointcutMock.type, pointcutMock.method, pointcutMock.descriptor) != null

    then:
    result.empty == emptyAdvices
    found == adviceFound

    where:
    pointcutMock                  | emptyAdvices | adviceFound
    stringConcatPointcut()        | true         | false
    stringConcatFactoryPointcut() | false        | true
  }

  void 'test constant pool introspector with invoke dynamic and constants'() {
    setup:
    final type = new TypeDescription.ForLoadedType(StringPlusConstantsExample)
    final advice = Stub(InvokeDynamicAdvice)
    final advices = Advices.fromCallSites([mockCallSites(advice, pointcutMock)])
    final introspector = Advices.AdviceIntrospector.ConstantPoolInstrospector.INSTANCE

    when:
    final result = introspector.findAdvices(advices, Stub(DynamicType.Builder), type, StringPlusConstantsExample.classLoader)
    final found = result.findAdvice(pointcutMock.type, pointcutMock.method, pointcutMock.descriptor) != null

    then:
    result.empty == emptyAdvices
    found == adviceFound

    where:
    pointcutMock                  | emptyAdvices | adviceFound
    stringConcatPointcut()        | true         | false
    stringConcatFactoryPointcut() | false        | true
  }
}
