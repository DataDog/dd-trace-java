package com.datadog.featureflag.evaluator

import com.datadog.featureflag.FeatureFlagEvaluatorImpl
import datadog.trace.api.featureflag.FeatureFlag
import datadog.trace.api.openfeature.Provider
import dev.openfeature.sdk.Client
import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FlagEvaluationDetails
import dev.openfeature.sdk.MutableContext
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.Structure
import dev.openfeature.sdk.Value
import spock.lang.Shared

class FeatureFlagEvaluatorTests extends BaseFeatureFlagsTest {

  @Shared
  protected Client client

  void setup() {
    final evaluator = new FeatureFlagEvaluatorImpl()
    evaluator.accept(configuration)
    FeatureFlag.EVALUATOR = evaluator
    OpenFeatureAPI.getInstance().setProviderAndWait(new Provider())
    client = OpenFeatureAPI.getInstance().getClient()
  }

  void 'test feature flag evaluation'() {
    setup:
    final testCase = input["testCase"] as TestCase
    final context = buildContext(testCase)

    when:
    final eval = evaluateDetails(testCase, context)

    then:
    final result = eval.value instanceof Value ? context.convertValue((Value) eval.value) : eval.value
    result == testCase.result.value

    where:
    input << testCases()
  }

  @SuppressWarnings('GroovyAssignabilityCheck')
  protected static EvaluationContext buildContext(final TestCase testCase) {
    final context = new MutableContext().setTargetingKey(testCase.getTargetingKey())
    testCase.attributes?.each {
      if (it.value instanceof Map) {
        context.add(it.key, Value.objectToValue(it.value).asStructure())
      } else if (it.value instanceof List) {
        context.add(it.key, Value.objectToValue(it.value).asList())
      } else if (it.value == null) {
        context.add(it.key, (Structure) null)
      } else {
        context.add(it.key, it.value)
      }
    }
    return context
  }

  protected FlagEvaluationDetails<?> evaluateDetails(final TestCase test, final EvaluationContext context) {
    switch (test.variationType) {
      case 'INTEGER':
        return client.getIntegerDetails(test.flag, test.defaultValue as Integer, context)
      case 'NUMERIC':
        return client.getDoubleDetails(test.flag, test.defaultValue as Double, context)
      case 'STRING':
        return client.getStringDetails(test.flag, test.defaultValue as String, context)
      case 'BOOLEAN':
        return client.getBooleanDetails(test.flag, test.defaultValue as Boolean, context)
      case 'OBJECT':
      case 'JSON':
        return client.getObjectDetails(test.flag, Value.objectToValue(test.defaultValue), context)
      default:
        throw new IllegalArgumentException("Invalid variation type ${test.variationType}")
    }
  }
}
