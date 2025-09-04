package excludefilter

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.FieldBackedContextStores
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter

import java.lang.reflect.Field
import java.util.concurrent.Executor

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE
import static excludefilter.ExcludeFilterTestInstrumentation.ExecutorExcludedRunnable
import static excludefilter.ExcludeFilterTestInstrumentation.ExecutorRunnable
import static excludefilter.ExcludeFilterTestInstrumentation.ExcludedExecutor
import static excludefilter.ExcludeFilterTestInstrumentation.ExcludedRunnable
import static excludefilter.ExcludeFilterTestInstrumentation.NormalExecutor
import static excludefilter.ExcludeFilterTestInstrumentation.NormalRunnable
import static excludefilter.ExcludeFilterTestInstrumentation.RunnableExcludedExecutor

class ExcludeFilterForkedTest extends InstrumentationSpecification {

  def "test ExcludeFilter #runnable.class.name"() {
    expect:
    ExcludeFilter.exclude(RUNNABLE, runnable) == excluded

    where:
    runnable               | excluded
    new ExcludedRunnable() | true
    new NormalRunnable()   | false
  }

  def "test field injection exclusion #clazz"() {
    setup:
    def runnableCheck = new InjectionCheck(clazz, Runnable, Object)
    def executorCheck = new InjectionCheck(clazz, Executor, Object)

    expect:
    runnableCheck.hasField() == hasRunnable
    runnableCheck.hasAccessorInterface() == hasAccessor
    executorCheck.hasField() == hasExecutor
    executorCheck.hasAccessorInterface() == hasAccessor

    where:
    clazz                    | hasRunnable | hasExecutor | hasAccessor
    ExcludedRunnable         | false       | false       | false
    NormalRunnable           | true        | false       | true
    RunnableExcludedExecutor | true        | false       | true
    ExcludedExecutor         | false       | false       | false
    NormalExecutor           | false       | true        | true
    ExecutorExcludedRunnable | false       | true        | true
    ExecutorRunnable         | true        | true        | true
  }

  static class InjectionCheck {
    private final boolean hasField
    private final boolean hasAccessorInterface

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
