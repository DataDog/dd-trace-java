package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.Advices
import net.bytebuddy.description.type.TypeDescription

import java.security.MessageDigest

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
    final pointcut = buildPointcut(String.getDeclaredMethod('concat', String))
    final advices = Advices.fromCallSites(mockAdvice(pointcut))

    when:
    final empty = advices.empty

    then:
    !empty
  }

  def 'test advices for type'() {
    setup:
    final pointcut = buildPointcut(String.getDeclaredMethod('concat', String))
    final advices = Advices.fromCallSites(mockAdvice(pointcut))

    when:
    final existing = advices.findAdvice(pointcut)

    then:
    existing != null

    when:
    final notFound  = advices.findAdvice('java/lang/Integer', pointcut.method(), pointcut.descriptor())

    then:
    notFound == null
  }

  def 'test multiple advices for type'() {
    setup:
    final startsWith1 = buildPointcut(String.getDeclaredMethod('startsWith', String))
    final startsWith2 = buildPointcut(String.getDeclaredMethod('startsWith', String, int.class))
    final advices = Advices.fromCallSites(mockAdvice(startsWith1), mockAdvice(startsWith2))

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
    final concatAdvice = mockAdvice(buildPointcut(String.getDeclaredMethod('concat', String)), 'foo.bar.Helper1', 'foo.bar.Helper2')
    final digestAdvice = mockAdvice(buildPointcut(MessageDigest.getDeclaredMethod('getInstance', String)), 'foo.bar.Helper3')
    final advices = Advices.fromCallSites([concatAdvice, digestAdvice])

    when:
    final helpers = advices.helpers

    then:
    helpers.toList().containsAll(['foo.bar.Helper1', 'foo.bar.Helper2', 'foo.bar.Helper3'])
  }

  def 'test advices with duplicated pointcut'() {
    setup:
    final pointcut = buildPointcut(String.getDeclaredMethod('concat', String))

    when:
    Advices.fromCallSites(mockAdvice(pointcut), mockAdvice(pointcut))

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

  def 'test constant pool introspector'() {
    setup:
    final target = StringConcatExample
    final targetType = Mock(TypeDescription) {
      getName() >> target.name
    }
    final advice = mockAdvice(pointcutMock)
    final advices = Advices.fromCallSites(advice)
    final introspector = Advices.AdviceIntrospector.ConstantPoolInstrospector.INSTANCE

    when: 'find is called without a class loader'
    def result = introspector.findAdvices(advices, targetType, null)

    then: 'no filtering should happen'
    result == advices

    when: 'find is called with a class loader'
    result = introspector.findAdvices(advices, targetType, StringConcatExample.classLoader)
    final found = result.findAdvice(pointcutMock) != null

    then: 'constant pool should be used to filter'
    result != advices
    result.empty == emptyAdvices
    found == adviceFound

    where:
    pointcutMock                                                          | emptyAdvices | adviceFound
    buildPointcut(String.getDeclaredMethod('concat', String))             | false         | true
    buildPointcut(MessageDigest.getDeclaredMethod('getInstance', String)) | true          | false
  }
}
