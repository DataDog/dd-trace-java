package com.datadog.featureflag

import static com.datadog.featureflag.utils.TestUtils.parseConfiguration

import com.datadog.featureflag.utils.TestUtils
import datadog.trace.api.featureflag.FeatureFlagEvaluator

class FeatureFlagEvaluatorTests extends AbstractJsonTestSuiteBasedTests {

  void 'test provider not ready'() {
    setup:
    final evaluator = new FeatureFlagEvaluatorImpl()
    final testCase = testCases.first()
    final context = buildContext(testCase)

    when:
    final eval = evaluateDetails(evaluator, testCase, context)

    then:
    eval.value == testCase.defaultValue
    eval.errorCode == FeatureFlagEvaluator.ResolutionError.PROVIDER_NOT_READY
  }

  void 'test no context'() {
    setup:
    final evaluator = new FeatureFlagEvaluatorImpl()
    evaluator.onConfigurationChanged(configuration)
    final testCase = testCases.first()

    when:
    final eval = evaluateDetails(evaluator, testCase, null)

    then:
    eval.value == testCase.defaultValue
    eval.errorCode == FeatureFlagEvaluator.ResolutionError.INVALID_CONTEXT
  }


  void 'test feature flag evaluation'() {
    setup:
    final evaluator = new FeatureFlagEvaluatorImpl()
    evaluator.onConfigurationChanged(configuration)
    final context = buildContext(evaluation)

    when:
    final eval = evaluateDetails(evaluator, evaluation, context)

    then:
    eval.value == evaluation.result.value

    where:
    evaluation << testCases
  }

  void 'test feature flag date parsing'() {
    setup:
    final evaluator = new FeatureFlagEvaluatorImpl()
    evaluator.onConfigurationChanged(parseConfiguration('data/flags-dates-v1.json'))
    final evaluation = new TestUtils.EvaluationTest(
      flag: 'start-and-end-date-test',
      variationType: 'STRING',
      defaultValue: 'unknown',
      targetingKey: 'alice',
      result: new TestUtils.EvaluationResult(
      value: 'invalid',
      variant: 'invalid'
      )
      )
    final context = buildContext(evaluation)

    when:
    final eval = evaluateDetails(evaluator, evaluation, context)

    then:
    eval.value == 'invalid'
  }
}
