import datadog.appsec.api.blocking.BlockingContentType
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.instrumentation.jersey2.MessageBodyReaderInstrumentation
import spock.lang.Shared

import java.util.function.BiFunction
import java.util.function.Supplier

class JerseyXmlRequestBodyTest extends AgentTestRunner {
  @Shared
  def ig

  static class TestCtx {
    boolean block
    String requestBodyConverted

    Flow<Void> getFlow() {
      if (block) {
        BlockingFlow.INSTANCE
      } else {
        Flow.ResultFlow.empty()
      }
    }
  }

  enum BlockingFlow implements Flow<Void> {
    INSTANCE

    @Override
    Action getAction() {
      new Action.RequestBlockingAction(403, BlockingContentType.JSON)
    }

    final Void result = null
  }

  def setupSpec() {
    Events<Object> events = Events.get()
    ig = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC)
    ig.registerCallback(events.requestHeader(), { RequestContext ctx, String name, String value ->
      if (name == 'x-block') {
        TestCtx testCtx = ctx.getData(RequestContextSlot.APPSEC)
        testCtx.block = true
      }
    } as TriConsumer<RequestContext, String, String>)
    ig.registerCallback(events.requestStarted(), { -> new Flow.ResultFlow<Object>(new TestCtx()) } as Supplier<Flow<Object>>)
    ig.registerCallback(events.requestBodyProcessed(), { RequestContext ctx, Object obj ->
      TestCtx testCtx = ctx.getData(RequestContextSlot.APPSEC)
      testCtx.requestBodyConverted = obj as String
      ctx.traceSegment.setTagTop('request.body.converted', obj as String)
      testCtx.flow
    } as BiFunction<RequestContext, Object, Flow<Void>>)
  }

  def cleanupSpec() {
    ig.reset()
  }

  def 'test XML request body parsing with simple XML'() {
    setup:
    def xmlContent = '<user><name>John</name><age>30</age></user>'

    expect:
    // Test the XML parsing logic directly
    def result = MessageBodyReaderInstrumentation.parseXmlToMap(xmlContent)
    result instanceof Map
    result.user instanceof Map
    result.user.name == 'John'
    result.user.age == '30'
  }

  def 'test XML request body parsing with attributes'() {
    setup:
    def xmlContent = '<user id="123" active="true"><name>Jane</name></user>'

    expect:
    def result = MessageBodyReaderInstrumentation.parseXmlToMap(xmlContent)
    result instanceof Map
    result.user instanceof Map
    result.user['@id'] == '123'
    result.user['@active'] == 'true'
    result.user.name == 'Jane'
  }

  def 'test XML request body parsing with nested elements'() {
    setup:
    def xmlContent = '''
      <order>
        <customer>
          <name>Alice</name>
          <address>
            <street>123 Main St</street>
            <city>Boston</city>
          </address>
        </customer>
        <items>
          <item>Book</item>
          <item>Pen</item>
        </items>
      </order>
    '''

    expect:
    def result = MessageBodyReaderInstrumentation.parseXmlToMap(xmlContent)
    result instanceof Map
    result.order instanceof Map
    result.order.customer instanceof Map
    result.order.customer.name == 'Alice'
    result.order.customer.address instanceof Map
    result.order.customer.address.street == '123 Main St'
    result.order.customer.address.city == 'Boston'
    result.order.items instanceof Map
    result.order.items.item instanceof List
    result.order.items.item.size() == 2
    result.order.items.item.contains('Book')
    result.order.items.item.contains('Pen')
  }

  def 'test XML request body parsing with XML declaration'() {
    setup:
    def xmlContent = '<?xml version="1.0" encoding="UTF-8"?><product><name>Widget</name><price>19.99</price></product>'

    expect:
    def result = MessageBodyReaderInstrumentation.parseXmlToMap(xmlContent)
    result instanceof Map
    result.product instanceof Map
    result.product.name == 'Widget'
    result.product.price == '19.99'
  }

  def 'test XML request body parsing with malformed XML returns null'() {
    setup:
    def xmlContent = '<user><name>John</name><age>30</user>' // Missing closing tag

    expect:
    def result = MessageBodyReaderInstrumentation.parseXmlToMap(xmlContent)
    result == null
  }

  def 'test XML content detection'() {
    expect:
    MessageBodyReaderInstrumentation.isXmlContent('<user><name>John</name></user>')
    MessageBodyReaderInstrumentation.isXmlContent('<?xml version="1.0"?><root/>')
    MessageBodyReaderInstrumentation.isXmlContent('  <data>test</data>  ')
    !MessageBodyReaderInstrumentation.isXmlContent('{"name": "John"}')
    !MessageBodyReaderInstrumentation.isXmlContent('plain text')
    !MessageBodyReaderInstrumentation.isXmlContent('')
    !MessageBodyReaderInstrumentation.isXmlContent(null)
  }

  def 'test XML security - XXE prevention'() {
    setup:
    def xmlContent = '''<?xml version="1.0"?>
      <!DOCTYPE user [
        <!ENTITY xxe SYSTEM "file:///etc/passwd">
      ]>
      <user><name>&xxe;</name></user>
    '''

    expect:
    // Should return null due to XXE prevention (DOCTYPE disabled)
    def result = MessageBodyReaderInstrumentation.parseXmlToMap(xmlContent)
    result == null
  }

  def 'test XML request body parsing with empty elements'() {
    setup:
    def xmlContent = '<user><name></name><age/><active>true</active></user>'

    expect:
    def result = MessageBodyReaderInstrumentation.parseXmlToMap(xmlContent)
    result instanceof Map
    result.user instanceof Map
    result.user.name == ''
    result.user.age == ''
    result.user.active == 'true'
  }

  def 'test XML request body parsing with CDATA'() {
    setup:
    def xmlContent = '<message><content><![CDATA[<script>alert("test")</script>]]></content></message>'

    expect:
    def result = MessageBodyReaderInstrumentation.parseXmlToMap(xmlContent)
    result instanceof Map
    result.message instanceof Map
    result.message.content == '<script>alert("test")</script>'
  }

  def 'test XML request body parsing with mixed content'() {
    setup:
    def xmlContent = '<note>This is <b>bold</b> text.</note>'

    expect:
    def result = MessageBodyReaderInstrumentation.parseXmlToMap(xmlContent)
    result instanceof Map
    result.note instanceof Map
    // Mixed content handling - should contain both text and element
    result.note.b == 'bold'
    result.note._text != null
  }

  def 'test XML request body parsing with namespaces'() {
    setup:
    def xmlContent = '''
      <root xmlns:ns="http://example.com">
        <ns:user>
          <ns:name>John</ns:name>
        </ns:user>
      </root>
    '''

    expect:
    def result = MessageBodyReaderInstrumentation.parseXmlToMap(xmlContent)
    result instanceof Map
    result.root instanceof Map
    // Namespace handling - should work with prefixed elements
    result.root['ns:user'] instanceof Map
    result.root['ns:user']['ns:name'] == 'John'
  }
}
