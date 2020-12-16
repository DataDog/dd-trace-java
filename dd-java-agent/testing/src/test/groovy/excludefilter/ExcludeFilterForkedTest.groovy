package excludefilter

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.FieldBackedContextStores
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter
import java.lang.reflect.Field
import java.util.concurrent.Callable

import static ExcludeFilterTestInstrumentation.*
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.*

class ExcludeFilterForkedTest extends AgentTestRunner {
  void configurePreAgent() {
    injectSysConfig("dd.trace.legacy.context.field.injection", "false")
  }

  def "test ExcludeFilter"() {
    expect:
    ExcludeFilter.exclude(RUNNABLE, runnable) == excluded

    where:
    runnable               | excluded
    new ExcludedRunnable() | true
    new NormalRunnable()   | false
  }

  def "test field injection exclusion"() {
    setup:
    def runnableCheck = new InjectionCheck(clazz, Runnable, Object)
    def callableCheck = new InjectionCheck(clazz, Callable, Object)

    expect:
    runnableCheck.hasField() == hasRunnable
    runnableCheck.hasAccessorInterface() == hasAccessor
    callableCheck.hasField() == hasCallable
    callableCheck.hasAccessorInterface() == hasAccessor

    where:
    clazz                    | hasRunnable | hasCallable | hasAccessor
    ExcludedRunnable         | false       | false       | false
    NormalRunnable           | true        | false       | true
    RunnableExcludedCallable | true        | false       | true
    ExcludedCallable         | false       | false       | false
    NormalCallable           | false       | true        | true
    CallableExcludedRunnable | false       | true        | true
    CallableRunnable         | true        | true        | true
  }

  static class InjectionCheck {
    private final boolean hasField
    private final boolean  hasAccessorInterface

    InjectionCheck(Class<?> clazz, Class<?> key, Class<?> value) {
      int storeId = FieldBackedContextStores.getContextStoreId(key.name, value.name)
      String fieldName = "__datadogContext\$${storeId}"
      boolean hasField = false
      for (Field field : clazz.getDeclaredFields()) {
        if (field.name == fieldName) {
          hasField = true
          break
        }
      }
      this.hasField = hasField

      boolean hasAccessorInterface = false
      for (Class inter : clazz.getInterfaces()) {
        if (inter.name == 'datadog.trace.bootstrap.FieldBackedContextAccessor') {
          hasAccessorInterface = true
        }
      }
      this.hasAccessorInterface = hasAccessorInterface
    }

    boolean hasField() {
      return hasField
    }

    boolean hasAccessorInterface() {
      return hasAccessorInterface
    }
  }
}
