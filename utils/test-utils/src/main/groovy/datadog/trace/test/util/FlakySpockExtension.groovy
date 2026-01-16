package datadog.trace.test.util

import org.spockframework.runtime.extension.AbstractGlobalExtension
import org.spockframework.runtime.model.NodeInfo
import org.spockframework.runtime.model.SpecInfo

import java.util.function.Predicate

/** Handle tests marked as {@link Flaky}. */
class FlakySpockExtension extends AbstractGlobalExtension {

  private static final String RUN_FLAKY_TESTS_KEY = "run.flaky.tests"

  @Override
  void visitSpec(final SpecInfo spec) {
    // By default, don't skip flaky tests. Useful for local development.
    if (shouldRunAll()) {
      return
    }

    // Otherwise, run only flaky tests if {RUN_FLAKY_TESTS_KEY} is true (-PrunFlakyTests gradle property) or only non-flay tests if it is false (-PskipFlakyTests gradle property).
    if (isFlakyTest(spec)) {
      if (shouldSkipFlakyTests()) {
        skip(spec)
      }
    } else {
      if (shouldRunFlakyTestsOnly()) {
        if (!spec.getAllFeatures().any { isFlakyTest(it.featureMethod) }) {
          skip(spec)
        } else {
          spec.getAllFeatures().each { feature ->
            if (!isFlakyTest(feature.featureMethod)) {
              skip(feature)
            }
          }
        }
      } else if (shouldSkipFlakyTests()) {
        spec.getAllFeatures().each { feature ->
          if (isFlakyTest(feature.featureMethod)) {
            skip(feature)
          }
        }
      }
    }
  }

  private static void skip(final node) {
    if (shouldRunFlakyTestsOnly()) {
      node.setExcluded(true)
    } else {
      node.setSkipped(true)
    }
  }

  private static boolean isFlakyTest(final NodeInfo node) {
    final flaky = node.getAnnotation(Flaky) as Flaky
    if (flaky == null) {
      return false
    }
    final condition = flaky.condition()
    if (!isFlakySpec(node, condition)) {
      return false
    }
    final suites = flaky.suites()
    if (suites == null || suites.length == 0) {
      return true
    }
    final specName = getSpecName(node)
    return suites.any { it == specName }
  }

  private static String getSpecName(final NodeInfo node) {
    def curNode = node
    while (curNode != null) {
      if (curNode instanceof SpecInfo) {
        return curNode.bottomSpec.name
      }
      curNode = curNode.parent
    }
    return null
  }

  private static boolean shouldRunAll() {
    return !shouldSkipFlakyTests() && !shouldRunFlakyTestsOnly()
  }

  private static boolean shouldSkipFlakyTests() {
    return "false" == System.getProperty(RUN_FLAKY_TESTS_KEY)
  }

  private static boolean shouldRunFlakyTestsOnly() {
    return "true" == System.getProperty(RUN_FLAKY_TESTS_KEY)
  }

  private static boolean isFlakySpec(final NodeInfo node, final Class<?> condition) {
    if (condition == null || condition === Flaky.True) {
      return true
    }
    boolean isFlaky
    if (Closure.isAssignableFrom(condition)) {
      // Invoke the closure without owner or this, also the spec parameter is captured by the groovy compiler
      // so we don't need to pass it along
      final closure = condition.newInstance(null, null) as Closure<Boolean>
      isFlaky = closure.doCall()
    } else {
      final closure = condition.newInstance() as Predicate<String>
      isFlaky = closure.test(node.name)
    }
    return isFlaky
  }
}
