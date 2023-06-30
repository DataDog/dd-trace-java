package datadog.trace.api.iast.csi

import spock.lang.Specification

import java.util.function.Function

class HasDynamicSupportTest extends Specification {

  def supplier

  void setup() {
    supplier = HasDynamicSupport.Loader.DYNAMIC_SUPPLIER
    HasDynamicSupport.Loader.DYNAMIC_SUPPLIER = supplier == null ? Mock(Function) : Spy(supplier)
  }

  void cleanup() {
    HasDynamicSupport.Loader.DYNAMIC_SUPPLIER = supplier
  }

  void 'test null supplier does nothing'() {
    setup:
    HasDynamicSupport.Loader.DYNAMIC_SUPPLIER = null

    when:
    final loaded = HasDynamicSupport.Loader.load(HasDynamicSupportTest.classLoader)

    then:
    loaded.empty
    0 * _
  }

  void 'test custom supplier'() {
    setup:
    final cl = HasDynamicSupportTest.classLoader

    when:
    final loaded = HasDynamicSupport.Loader.load(cl)

    then:
    1 * HasDynamicSupport.Loader.DYNAMIC_SUPPLIER.apply(cl) >> [CustomDynamicSupport]
    loaded.size() == 1
    loaded[0] == CustomDynamicSupport
  }

  static class CustomDynamicSupport implements HasDynamicSupport {
  }
}
