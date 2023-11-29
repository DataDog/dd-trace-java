package datadog.trace.bootstrap.instrumentation.api


import datadog.trace.test.util.DDSpecification

class TaskWrapperTest extends DDSpecification {

  def "test unwrapping"() {
    when: "direct wrapping"
    def directWrapper = new DirectWrapper()
    then:
    TaskWrapper.getUnwrappedType(directWrapper) == Integer

    when: "wrapped value is null"
    def nullWrapper = new RecursiveWrapper()
    then:
    TaskWrapper.getUnwrappedType(nullWrapper) == null

    when: "recursive wrapped value within depth limit"
    def shallowRecursiveWrapper = new RecursiveWrapper()
    shallowRecursiveWrapper.wrapped = new DirectWrapper()
    then:
    TaskWrapper.getUnwrappedType(shallowRecursiveWrapper) == Integer

    when: "recursive wrapped value exceeds depth limit"
    def l1 = new DirectWrapper()
    def l2 = new RecursiveWrapper()
    l2.wrapped = l1
    def l3 = new RecursiveWrapper()
    l3.wrapped = l2
    def l4 = new RecursiveWrapper()
    l4.wrapped = l3
    def l5 = new RecursiveWrapper()
    l5.wrapped = l4
    def l6 = new RecursiveWrapper()
    l6.wrapped = l5
    def deepRecursiveWrapper = l6
    then: "terminate at max depth"
    TaskWrapper.getUnwrappedType(deepRecursiveWrapper) == DirectWrapper

    when: "cycle"
    def outer = new RecursiveWrapper()
    def inner = new RecursiveWrapper()
    outer.wrapped = inner
    inner.wrapped = outer
    then: "terminate at max depth"
    TaskWrapper.getUnwrappedType(outer) == RecursiveWrapper
    TaskWrapper.getUnwrappedType(inner) == RecursiveWrapper
  }


  class RecursiveWrapper implements TaskWrapper {

    TaskWrapper wrapped

    @SuppressWarnings('MethodName')
    @Override
    Object $$DD$$__unwrap() {
      return wrapped
    }
  }

  class DirectWrapper implements TaskWrapper {

    Integer field = 1

    @SuppressWarnings('MethodName')
    @Override
    Object $$DD$$__unwrap() {
      return field
    }
  }
}
