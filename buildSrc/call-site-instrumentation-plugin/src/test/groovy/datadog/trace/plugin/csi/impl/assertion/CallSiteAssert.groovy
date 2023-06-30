package datadog.trace.plugin.csi.impl.assertion

import java.lang.reflect.Method

class CallSiteAssert {

  protected Collection<Class<?>> interfaces
  protected Collection<String> helpers
  protected Collection<AdviceAssert> advices
  protected Method enabled
  protected Collection<String> enabledArgs

  void interfaces(Class<?>... values) {
    final list = values.toList()
    assert interfaces == list
  }

  void helpers(Class<?>... values) {
    helpers(values*.name as String[])
  }

  void helpers(String... values) {
    final list = values.toList()
    assert helpers == list
  }

  void advices(int index, @DelegatesTo(AdviceAssert) Closure closure) {
    final asserter = advices[index]
    closure.delegate = asserter
    closure(asserter)
  }

  void enabled(Method method, String... args) {
    assert method == enabled
    final list = args.toList()
    assert list == enabledArgs
  }
}
