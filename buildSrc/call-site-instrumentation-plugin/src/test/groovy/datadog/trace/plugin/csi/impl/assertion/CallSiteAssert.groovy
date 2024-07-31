package datadog.trace.plugin.csi.impl.assertion

import java.lang.reflect.Method

class CallSiteAssert {

  protected Collection<Class<?>> interfaces
  protected Collection<Class<?>> spi
  protected Collection<Class<?>> helpers
  protected Collection<AdviceAssert> advices
  protected Method enabled
  protected Collection<String> enabledArgs

  void interfaces(Class<?>... values) {
    assertList(values.toList(), interfaces)
  }

  void helpers(Class<?>... values) {
    assertList(values.toList(), helpers)
  }

  void spi(Class<?>...values) {
    assertList(values.toList(), spi)
  }

  void advices(int index, @DelegatesTo(AdviceAssert) Closure closure) {
    final asserter = advices[index]
    closure.delegate = asserter
    closure(asserter)
  }

  void enabled(Method method, String... args) {
    assert method == enabled
    assertList(args.toList(), enabledArgs)
  }

  private static <E> void assertList(final Collection<E> received, final Collection<E> expected) {
    assert received.size() == expected.size() && received.containsAll(expected)
  }
}
