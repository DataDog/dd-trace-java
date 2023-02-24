package datadog.trace.test.util

import org.spockframework.runtime.extension.AbstractGlobalExtension
import org.spockframework.runtime.model.NodeInfo
import org.spockframework.runtime.model.SpecInfo

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
        if (!spec.getAllFeatures().any {isFlakyTest(it.featureMethod) }) {
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
}
