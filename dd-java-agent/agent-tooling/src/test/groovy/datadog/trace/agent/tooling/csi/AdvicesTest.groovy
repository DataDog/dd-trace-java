package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.Advices
import net.bytebuddy.description.type.TypeDescription

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.HasFlags.COMPUTE_MAX_STACK

class AdvicesTest extends BaseCallSiteTest {

  def 'test empty advices'() {
    setup:
    final advices = Advices.fromCallSites()

    when:
    final empty = advices.empty

    then:
    empty
    advices == Advices.EMPTY
  }

  def 'test non empty advices'() {
    setup:
    final pointcut = stringConcatPointcut()
    final advices = Advices.fromCallSites(mockInvokeAdvice(pointcut))

    when:
    final empty = advices.empty

    then:
    !empty
  }

  def 'test advices for type'() {
    setup:
    final pointcut = stringConcatPointcut()
    final advices = Advices.fromCallSites(mockInvokeAdvice(pointcut))

    when:
    final existing = advices.findAdvice(pointcut)

    then:
    existing != null

    when:
    final notFound = advices.findAdvice('java/lang/Integer', pointcut.method(), pointcut.descriptor())

    then:
    notFound == null
  }

  def 'test multiple advices for type'() {
    setup:
    final startsWith1 = buildPointcut(String.getDeclaredMethod('startsWith', String))
    final startsWith2 = buildPointcut(String.getDeclaredMethod('startsWith', String, int.class))
    final advices = Advices.fromCallSites(mockInvokeAdvice(startsWith1), mockInvokeAdvice(startsWith2))

    when:
    final startsWith1Found = advices.findAdvice(startsWith1)

    then:
    startsWith1Found != null

    when:
    final startsWith2Found = advices.findAdvice(startsWith2)

    then:
    startsWith2Found != null
  }

  def 'test helper class names'() {
    setup:
    final concatAdvice = mockInvokeAdvice(stringConcatPointcut(), 'foo.bar.Helper1', 'foo.bar.Helper2')
    final digestAdvice = mockInvokeAdvice(messageDigestGetInstancePointcut(), 'foo.bar.Helper3')
    final advices = Advices.fromCallSites([concatAdvice, digestAdvice])

    when:
    final helpers = advices.helpers

    then:
    helpers.toList().containsAll(['foo.bar.Helper1', 'foo.bar.Helper2', 'foo.bar.Helper3'])
  }

  def 'test advices with duplicated pointcut'() {
    setup:
    final pointcut = stringConcatPointcut()

    when:
    Advices.fromCallSites(mockInvokeAdvice(pointcut), mockInvokeAdvice(pointcut))

    then:
    thrown(UnsupportedOperationException)
  }

  def 'test no op advice instrospector'() {
    setup:
    final introspector = Advices.AdviceIntrospector.NoOpAdviceInstrospector.INSTANCE
    final advices = Advices.fromCallSites()

    when:
    final result = introspector.findAdvices(advices, Mock(TypeDescription), Mock(ClassLoader))

    then:
    result == advices
  }

  def 'test constant pool introspector'(final Pointcut pointcutMock,
    final boolean emptyAdvices,
    final boolean adviceFound) {
    setup:
    final target = StringConcatExample
    final targetType = Mock(TypeDescription) {
      getName() >> target.name
    }
    final advice = mockInvokeAdvice(pointcutMock)
    final advices = Advices.fromCallSites(advice)
    final introspector = Advices.AdviceIntrospector.ConstantPoolInstrospector.INSTANCE

    when:
    final result = introspector.findAdvices(advices, targetType, StringConcatExample.classLoader)
    final found = result.findAdvice(pointcutMock) != null

    then:
    result.empty == emptyAdvices
    found == adviceFound

    where:
    pointcutMock                       | emptyAdvices | adviceFound
    stringConcatPointcut()             | false        | true
    messageDigestGetInstancePointcut() | true         | false
  }

  def 'test compute max stack flag'() {
    when:
    def advices = Advices.EMPTY

    then:
    !advices.computeMaxStack()

    when:
    advices = Advices.fromCallSites([mockInvokeAdvice(stringConcatPointcut(), 0)])

    then:
    !advices.computeMaxStack()

    when:
    advices = Advices.fromCallSites([
      mockInvokeAdvice(stringConcatPointcut(), 0),
      mockInvokeAdvice(messageDigestGetInstancePointcut(), COMPUTE_MAX_STACK),
      mockInvokeAdvice(stringConcatFactoryPointcut(), 0)
    ])

    then:
    advices.computeMaxStack()
  }
}
