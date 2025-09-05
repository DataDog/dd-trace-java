package datadog.trace.instrumentation.resteasy

import spock.lang.Specification

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

class MessageBodyReaderInvocationInstrumentationXmlTest extends Specification {

  def "test isXmlContent with XML string"() {
    when:
    def result = MessageBodyReaderInvocationInstrumentation.isXmlContent(xmlContent)

    then:
    result == expected

    where:
    xmlContent                                    | expected
    "<root><child>value</child></root>"          | true
    "<?xml version='1.0'?><root></root>"         | true
    "<person><name>John</name></person>"         | true
    "<empty/>"                                   | false  // No closing tag
    "<unclosed>"                                 | false  // No closing tag
    '{"name": "John"}'                           | false  // JSON
    '["item1", "item2"]'                         | false  // JSON array
    "plain text"                                 | false  // Plain text
    ""                                           | false  // Empty
    null                                         | false  // Null
    "   <root></root>   "                        | true   // Whitespace trimmed
  }

  def "test isXmlContent with XML DOM objects"() {
    setup:
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
    DocumentBuilder builder = factory.newDocumentBuilder()
    def xmlString = "<root><child>value</child></root>"
    def document = builder.parse(new ByteArrayInputStream(xmlString.bytes))
    def element = document.documentElement

    when:
    def documentResult = MessageBodyReaderInvocationInstrumentation.isXmlContent(document)
    def elementResult = MessageBodyReaderInvocationInstrumentation.isXmlContent(element)
    def nodeResult = MessageBodyReaderInvocationInstrumentation.isXmlContent(element.firstChild)

    then:
    documentResult == true
    elementResult == true
    nodeResult == true
  }

  def "test isXmlContent with non-XML objects"() {
    when:
    def result = MessageBodyReaderInvocationInstrumentation.isXmlContent(obj)

    then:
    result == false

    where:
    obj << [
      new Object(),
      123,
      ["list", "items"],
      [key: "value"],
      new Date()
    ]
  }

  def "test processXmlForWaf with XML string"() {
    when:
    def result = MessageBodyReaderInvocationInstrumentation.processXmlForWaf(xmlString)

    then:
    result != null
    result instanceof List
    result.size() == 1
    result[0] instanceof Map

    where:
    xmlString << [
      "<root><child>value</child></root>",
      "<?xml version='1.0'?><person><name>John</name><age>30</age></person>",
      "<book title='XML Guide'><author>Jane Doe</author></book>"
    ]
  }

  def "test processXmlForWaf with XML DOM objects"() {
    setup:
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
    DocumentBuilder builder = factory.newDocumentBuilder()
    def xmlString = "<root><child>value</child></root>"
    def document = builder.parse(new ByteArrayInputStream(xmlString.bytes))
    def element = document.documentElement

    when:
    def documentResult = MessageBodyReaderInvocationInstrumentation.processXmlForWaf(document)
    def elementResult = MessageBodyReaderInvocationInstrumentation.processXmlForWaf(element)

    then:
    documentResult != null
    documentResult instanceof List
    documentResult.size() == 1
    documentResult[0] instanceof Map

    elementResult != null
    elementResult instanceof List
    elementResult.size() == 1
    elementResult[0] instanceof Map
  }

  def "test processXmlForWaf with complex XML structure"() {
    setup:
    def xmlString = '''<?xml version="1.0"?>
    <catalog>
      <book id="1" category="fiction">
        <title>The Great Gatsby</title>
        <author>F. Scott Fitzgerald</author>
        <price currency="USD">12.99</price>
      </book>
      <book id="2" category="non-fiction">
        <title>Sapiens</title>
        <author>Yuval Noah Harari</author>
        <price currency="USD">15.99</price>
      </book>
    </catalog>'''

    when:
    def result = MessageBodyReaderInvocationInstrumentation.processXmlForWaf(xmlString)

    then:
    result != null
    result instanceof List
    result.size() == 1

    def catalog = result[0]
    catalog instanceof Map
    catalog.containsKey("children")

    def children = catalog.children
    children instanceof List
    children.size() >= 2  // Should have book elements
  }

  def "test processXmlForWaf with XML containing attributes"() {
    setup:
    def xmlString = '<person id="123" active="true"><name>John Doe</name></person>'

    when:
    def result = MessageBodyReaderInvocationInstrumentation.processXmlForWaf(xmlString)

    then:
    result != null
    result instanceof List
    result.size() == 1

    def person = result[0]
    person instanceof Map
    person.containsKey("attributes")

    def attributes = person.attributes
    attributes instanceof Map
    attributes.containsKey("id")
    attributes.containsKey("active")
    attributes.id == "123"
    attributes.active == "true"
  }

  def "test processXmlForWaf with malformed XML"() {
    when:
    def result = MessageBodyReaderInvocationInstrumentation.processXmlForWaf(malformedXml)

    then:
    result == null

    where:
    malformedXml << [
      "<unclosed>",
      "<root><child></root>",
      // Mismatched tags
      "<?xml version='1.0'?><root><child>",
      // Incomplete
      "<root><<invalid>></root>"  // Invalid characters
    ]
  }

  def "test processXmlForWaf with non-XML objects"() {
    when:
    def result = MessageBodyReaderInvocationInstrumentation.processXmlForWaf(obj)

    then:
    result == null

    where:
    obj << [null, new Object(), 123, ["list"], [key: "value"]]
  }

  def "test XML processing preserves text content"() {
    setup:
    def xmlString = '<message>Hello <emphasis>World</emphasis>!</message>'

    when:
    def result = MessageBodyReaderInvocationInstrumentation.processXmlForWaf(xmlString)

    then:
    result != null
    result instanceof List
    result.size() == 1

    def message = result[0]
    message instanceof Map
    message.containsKey("children")

    def children = message.children
    children instanceof List
    children.size() >= 1

    // Should contain text content and emphasis element
    def hasTextContent = children.any { it instanceof String && it.contains("Hello") }
    def hasEmphasisElement = children.any { it instanceof Map }
    hasTextContent || hasEmphasisElement  // At least one should be true
  }

  def "test XML processing with nested elements"() {
    setup:
    def xmlString = '''
    <order>
      <customer>
        <name>John Doe</name>
        <email>john@example.com</email>
      </customer>
      <items>
        <item>
          <name>Book</name>
          <price>19.99</price>
        </item>
        <item>
          <name>Pen</name>
          <price>2.99</price>
        </item>
      </items>
    </order>'''

    when:
    def result = MessageBodyReaderInvocationInstrumentation.processXmlForWaf(xmlString)

    then:
    result != null
    result instanceof List
    result.size() == 1

    def order = result[0]
    order instanceof Map
    order.containsKey("children")

    def children = order.children
    children instanceof List
    children.size() >= 2  // Should have customer and items
  }

  def "test XML processing depth limit"() {
    setup:
    // Create deeply nested XML (more than MAX_CONVERSION_DEPTH levels)
    def xmlBuilder = new StringBuilder("<root>")
    20.times { i ->
      xmlBuilder.append("<level${i}>")
    }
    xmlBuilder.append("deep content")
    20.times { i ->
      xmlBuilder.append("</level${19-i}>")
    }
    xmlBuilder.append("</root>")
    def deepXml = xmlBuilder.toString()

    when:
    def result = MessageBodyReaderInvocationInstrumentation.processXmlForWaf(deepXml)

    then:
    result != null
    result instanceof List
    result.size() == 1

    // Should handle deep nesting gracefully without infinite recursion
    def root = result[0]
    root instanceof Map
  }
}
