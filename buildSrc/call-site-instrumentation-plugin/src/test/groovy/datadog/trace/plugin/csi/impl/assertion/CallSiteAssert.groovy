package datadog.trace.plugin.csi.impl.assertion

import java.lang.reflect.Method

class CallSiteAssert {

  protected Collection<Class<?>> interfaces
  protected Collection<Class<?>> helpers
  protected Collection<AdviceAssert> advices
  protected Method enabled
  protected Collection<String> enabledArgs

  void interfaces(Class<?>... values) {
    assert values.toList() == interfaces
  }

  void helpers(Class<?>... values) {
    assert values.toList() == helpers
  }

  void advices(int index, @DelegatesTo(AdviceAssert) Closure closure) {
    final asserter = advices[index]
    closure.delegate = asserter
    closure(asserter)
  }

  void enabled(Method method, String... args) {
    assert method == enabled
    assert args.toList() == enabledArgs
  }
}
