package com.datadog.appsec.ddwaf

import datadog.trace.test.util.DDSpecification

class TraceTaggingRuleSpecification extends DDSpecification {

  def 'should create rule with output configuration'() {
    setup:
    def tags = [type: 'sql_injection', category: 'attack_attempt']
    def attributes = [
      'appsec.rule.id': WAFResultData.AttributeValue.literal('test-rule-123'),
      'appsec.confidence': WAFResultData.AttributeValue.literal(0.95)
    ]
    def output = new WAFResultData.Output(true, false, attributes)

    when:
    def rule = new WAFResultData.Rule()
    rule.id = 'test-rule-123'
    rule.name = 'Test Rule'
    rule.tags = tags
    rule.output = output

    then:
    rule.id == 'test-rule-123'
    rule.name == 'Test Rule'
    rule.tags == tags
    rule.output == output
    rule.shouldKeepTrace()
    !rule.shouldGenerateEvents()
    rule.getAttributes() == attributes
  }

  def 'should create rule without output configuration'() {
    setup:
    def tags = [type: 'xss', category: 'attack_attempt']

    when:
    def rule = new WAFResultData.Rule()
    rule.id = 'test-rule-456'
    rule.name = 'Test Rule 2'
    rule.tags = tags
    rule.output = null

    then:
    rule.id == 'test-rule-456'
    rule.name == 'Test Rule 2'
    rule.tags == tags
    rule.output == null
    rule.shouldKeepTrace() // Default to true when output is null
    rule.shouldGenerateEvents() // Default to true when output is null
    rule.getAttributes() == null
  }

  def 'should handle output with null values'() {
    setup:
    def output = new WAFResultData.Output(null, null, null)

    when:
    def rule = new WAFResultData.Rule()
    rule.id = 'test-rule-789'
    rule.name = 'Test Rule 3'
    rule.tags = [:]
    rule.output = output

    then:
    rule.shouldKeepTrace() // Default to true when keep is null
    rule.shouldGenerateEvents() // Default to true when event is null
    rule.getAttributes() == null
  }

  def 'should handle output with partial configuration'() {
    setup:
    def attributes = ['appsec.attack.type': WAFResultData.AttributeValue.literal('sql_injection')]
    def output = new WAFResultData.Output(true, true, attributes)

    when:
    def rule = new WAFResultData.Rule()
    rule.id = 'test-rule-partial'
    rule.name = 'Test Rule Partial'
    rule.tags = [:]
    rule.output = output

    then:
    rule.shouldKeepTrace()
    rule.shouldGenerateEvents()
    rule.getAttributes() == attributes
  }

  def 'should handle empty attributes map'() {
    setup:
    def output = new WAFResultData.Output(false, true, [:])

    when:
    def rule = new WAFResultData.Rule()
    rule.id = 'test-rule-empty'
    rule.name = 'Test Rule Empty'
    rule.tags = [:]
    rule.output = output

    then:
    !rule.shouldKeepTrace()
    rule.shouldGenerateEvents()
    rule.getAttributes() == [:]
  }

  def 'should create literal attribute values'() {
    when:
    def stringValue = WAFResultData.AttributeValue.literal('test-string')
    def numberValue = WAFResultData.AttributeValue.literal(42)
    def booleanValue = WAFResultData.AttributeValue.literal(true)
    def nullValue = WAFResultData.AttributeValue.literal(null)

    then:
    stringValue.isLiteral()
    stringValue.getLiteralValue() == 'test-string'
    numberValue.isLiteral()
    numberValue.getLiteralValue() == 42
    booleanValue.isLiteral()
    booleanValue.getLiteralValue() == true
    nullValue.isLiteral()
    nullValue.getLiteralValue() == null
  }

  def 'should reject non-scalar literal values'() {
    when:
    WAFResultData.AttributeValue.literal([1, 2, 3])

    then:
    thrown(IllegalArgumentException)
  }

  def 'should create request data attribute values'() {
    setup:
    def keyPath = ['user', 'name']
    def transformers = ['lowercase']

    when:
    def attrValue = WAFResultData.AttributeValue.fromRequestData('server.request.headers', keyPath, transformers)

    then:
    !attrValue.isLiteral()
    attrValue.getAddress() == 'server.request.headers'
    attrValue.getKeyPath() == keyPath
    attrValue.getTransformers() == transformers
  }

  def 'should reject null or empty address'() {
    when:
    WAFResultData.AttributeValue.fromRequestData(null, [], [])

    then:
    thrown(IllegalArgumentException)

    when:
    WAFResultData.AttributeValue.fromRequestData('', [], [])

    then:
    thrown(IllegalArgumentException)

    when:
    WAFResultData.AttributeValue.fromRequestData('   ', [], [])

    then:
    thrown(IllegalArgumentException)
  }

  def 'should reject forbidden addresses'() {
    when:
    WAFResultData.AttributeValue.fromRequestData('usr.session_id', [], [])

    then:
    thrown(IllegalArgumentException)

    when:
    WAFResultData.AttributeValue.fromRequestData('server.request.cookies', [], [])

    then:
    thrown(IllegalArgumentException)
  }

  def 'should check forbidden addresses'() {
    expect:
    WAFResultData.isForbiddenAddress('usr.session_id')
    WAFResultData.isForbiddenAddress('server.request.cookies')
    !WAFResultData.isForbiddenAddress('server.request.headers')
    !WAFResultData.isForbiddenAddress('server.request.body')
  }

  def 'should get forbidden addresses set'() {
    when:
    def forbiddenAddresses = WAFResultData.getForbiddenAddresses()

    then:
    forbiddenAddresses.contains('usr.session_id')
    forbiddenAddresses.contains('server.request.cookies')
    forbiddenAddresses.size() == 2
  }

  def 'should handle attribute value toString'() {
    setup:
    def literalValue = WAFResultData.AttributeValue.literal('test')
    def requestDataValue = WAFResultData.AttributeValue.fromRequestData('server.request.headers', ['user-agent'], ['lowercase'])

    expect:
    literalValue.toString().contains('literal=test')
    requestDataValue.toString().contains("address='server.request.headers'")
    requestDataValue.toString().contains('keyPath=[user-agent]')
    requestDataValue.toString().contains('transformers=[lowercase]')
  }
}