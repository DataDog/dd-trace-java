package com.datadog.featureflag

import static com.datadog.featureflag.utils.TestUtils.EvaluationTest
import static com.datadog.featureflag.utils.TestUtils.parseConfiguration
import static com.datadog.featureflag.utils.TestUtils.testCases
import static datadog.trace.api.featureflag.FeatureFlagEvaluator.Context
import static datadog.trace.api.featureflag.FeatureFlagEvaluator.Resolution

import com.datadog.featureflag.ufc.v1.ServerConfiguration
import datadog.trace.api.featureflag.FeatureFlagEvaluator
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class AbstractJsonTestSuiteBasedTests extends DDSpecification {

  @Shared
  protected ServerConfiguration configuration

  @Shared
  protected List<EvaluationTest> testCases

  void setupSpec() {
    configuration = parseConfiguration('data/flags-v1.json')
    testCases = testCases()
  }

  @SuppressWarnings('GroovyAssignabilityCheck')
  protected static Context buildContext(final EvaluationTest testCase) {
    return new Context() {
        @Override
        String getTargetingKey() {
          return testCase.targetingKey
        }

        @Override
        Set<String> keySet() {
          return testCase.attributes.keySet()
        }

        @Override
        Object getValue(final String key) {
          return testCase.attributes.get(key)
        }
      }
  }

  protected Resolution<?> evaluateDetails(final FeatureFlagEvaluator evaluator, final EvaluationTest testCase, final Context context) {
    switch (testCase.variationType) {
      case 'INTEGER':
        return evaluator.evaluate(testCase.flag, testCase.defaultValue as Integer, context)
      case 'NUMERIC':
        return evaluator.evaluate(testCase.flag, testCase.defaultValue as Double, context)
      case 'STRING':
        return evaluator.evaluate(testCase.flag, testCase.defaultValue as String, context)
      case 'BOOLEAN':
        return evaluator.evaluate(testCase.flag, testCase.defaultValue as Boolean, context)
      case 'OBJECT':
      case 'JSON':
        return evaluator.evaluate(testCase.flag, testCase.defaultValue, context)
      default:
        throw new IllegalArgumentException("Invalid variation type ${testCase.variationType}")
    }
  }
}
