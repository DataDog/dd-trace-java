package datadog.trace.plugin.csi.impl.assertion

import java.lang.reflect.Method

import static java.util.Arrays.asList

class CallSiteAssert {

  protected Set<Class<?>> interfaces
  protected Set<Class<?>> spi
  protected Set<Class<?>> helpers
  protected Collection<AdviceAssert> advices
  protected Method enabled
  protected Set<String> enabledArgs

  void interfaces(Class<?>... values) {
    assertSameElements(interfaces, values)
  }

  void helpers(Class<?>... values) {
    assertSameElements(helpers, values)
  }

  void spi(Class<?>...values) {
    assertSameElements(spi, values)
  }

  void advices(int index, @DelegatesTo(AdviceAssert) Closure closure) {
    final asserter = advices[index]
    closure.delegate = asserter
    closure(asserter)
  }

  void enabled(Method method, String... args) {
    assert method == enabled
    assertSameElements(enabledArgs, args)
  }

  private static <E> void assertSameElements(final Set<E> expected, final E...received) {
    assert received.length == expected.size() && expected.containsAll(asList(received))
  }
}
