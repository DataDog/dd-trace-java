package excludefilter

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter
import java.lang.reflect.Field
import java.util.concurrent.Callable

import static ExcludeFilterTestInstrumentation.*
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.*

class ExcludeFilterLegacyForkedTest extends AgentTestRunner {
  void configurePreAgent() {
    injectSysConfig("dd.trace.legacy.context.field.injection", "true")
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
    runnableCheck.hasMarkerInterface() == hasMarker
    runnableCheck.hasAccessorInterface() == hasRunnable
    callableCheck.hasField() == hasCallable
    callableCheck.hasMarkerInterface() == hasMarker
    callableCheck.hasAccessorInterface() == hasCallable

    where:
    clazz                    | hasRunnable | hasCallable | hasMarker
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
    private final boolean  hasMarkerInterface
    private final boolean  hasAccessorInterface

    InjectionCheck(Class<?> clazz, Class<?> key, Class<?> value) {
      String fieldName = "__datadogContext\$${key.name.replace('.', '$')}"
      String accessorName = ("datadog.trace.bootstrap.instrumentation.context.FieldBackedProvider\$ContextAccessor\$"
        + "${key.name.replace('.', '$')}\$${value.name.replace('.', '$')}")
      boolean hasField = false
      for (Field field : clazz.getDeclaredFields()) {
        if (field.name == fieldName) {
          hasField = true
          break
        }
      }
      this.hasField = hasField

      boolean hasMarkerInterface = false
      boolean hasAccessorInterface = false
      for (Class inter : clazz.getInterfaces()) {
        if (inter.name == 'datadog.trace.bootstrap.FieldBackedContextStoreAppliedMarker') {
          hasMarkerInterface = true
        }
        if (inter.name == accessorName) {
          hasAccessorInterface = true
        }
      }
      this.hasMarkerInterface = hasMarkerInterface
      this.hasAccessorInterface = hasAccessorInterface
    }

    boolean hasField() {
      return hasField
    }

    boolean hasMarkerInterface() {
      return hasMarkerInterface
    }

    boolean hasAccessorInterface() {
      return hasAccessorInterface
    }
  }
}
