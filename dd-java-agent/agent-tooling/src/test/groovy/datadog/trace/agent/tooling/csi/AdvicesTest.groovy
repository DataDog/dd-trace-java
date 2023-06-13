package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.Advices

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
    final advices = Advices.fromCallSites(mockCallSites(Mock(InvokeAdvice), pointcut))

    when:
    final empty = advices.empty

    then:
    !empty
  }

  def 'test advices for type'() {
    setup:
    final pointcut = stringConcatPointcut()
    final advices = Advices.fromCallSites(mockCallSites(Mock(InvokeAdvice), pointcut))

    when:
    final existing = advices.findAdvice(pointcut.type, pointcut.method, pointcut.descriptor)

    then:
    existing != null

    when:
    final notFound = advices.findAdvice('java/lang/Integer', pointcut.method, pointcut.descriptor)

    then:
    notFound == null
  }

  def 'test multiple advices for type'() {
    setup:
    final startsWith1 = buildPointcut(String.getDeclaredMethod('startsWith', String))
    final startsWith2 = buildPointcut(String.getDeclaredMethod('startsWith', String, int.class))
    final advices = Advices.fromCallSites(mockCallSites(Mock(InvokeAdvice), startsWith1), mockCallSites(Mock(InvokeAdvice), startsWith2))

    when:
    final startsWith1Found = advices.findAdvice(startsWith1.type, startsWith1.method, startsWith1.descriptor)

    then:
    startsWith1Found != null

    when:
    final startsWith2Found = advices.findAdvice(startsWith2.type, startsWith2.method, startsWith2.descriptor)

    then:
    startsWith2Found != null
  }

  def 'test helper class names'() {
    setup:
    final concatAdvice = mockCallSites(Mock(InvokeAdvice), stringConcatPointcut(), 'foo.bar.Helper1', 'foo.bar.Helper2')
    final digestAdvice = mockCallSites(Mock(InvokeAdvice), messageDigestGetInstancePointcut(), 'foo.bar.Helper3')
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
    Advices.fromCallSites(mockCallSites(Mock(InvokeAdvice), pointcut), mockCallSites(Mock(InvokeAdvice), pointcut))

    then:
    thrown(UnsupportedOperationException)
  }

  def 'test no op advice instrospector'() {
    setup:
    final introspector = Advices.AdviceIntrospector.NoOpAdviceInstrospector.INSTANCE
    final advices = Advices.fromCallSites()

    when:
    final result = introspector.findAdvices(advices, [] as byte[])

    then:
    result == advices
  }

  void 'test constant pool introspector'() {
    setup:
    final target = loadClass(StringConcatExample)
    final advice = mockCallSites(Mock(InvokeAdvice), pointcutMock)
    final advices = Advices.fromCallSites(advice)
    final introspector = Advices.AdviceIntrospector.ConstantPoolInstrospector.INSTANCE

    when:
    final result = introspector.findAdvices(advices, target)
    final found = result.findAdvice(pointcutMock.type, pointcutMock.method, pointcutMock.descriptor) != null

    then:
    result.empty == emptyAdvices
    found == adviceFound

    where:
    pointcutMock                       | emptyAdvices | adviceFound
    stringConcatPointcut()             | false        | true
    messageDigestGetInstancePointcut() | true         | false
  }

  private static byte[] loadClass(final Class<?> clazz) {
    return clazz.getResourceAsStream("${clazz.simpleName}.class").bytes
  }
}
