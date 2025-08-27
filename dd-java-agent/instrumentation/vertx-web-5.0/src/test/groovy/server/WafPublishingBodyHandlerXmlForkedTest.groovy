package server

import datadog.trace.instrumentation.vertx_5_0.server.WafPublishingBodyHandler
import spock.lang.Specification

import java.lang.reflect.Method

class WafPublishingBodyHandlerXmlForkedTest extends Specification {

  def "test isXmlContent with XML declaration"() {
    given:
    def isXmlContentMethod = getIsXmlContentMethod()

    when:
    def result = isXmlContentMethod.invoke(null, "<?xml version=\"1.0\"?><root></root>")

    then:
    result == true
  }

  def "test isXmlContent with simple XML element"() {
    given:
    def isXmlContentMethod = getIsXmlContentMethod()

    when:
    def result = isXmlContentMethod.invoke(null, "<users><user>test</user></users>")

    then:
    result == true
  }

  def "test isXmlContent with whitespace"() {
    given:
    def isXmlContentMethod = getIsXmlContentMethod()

    when:
    def result = isXmlContentMethod.invoke(null, "  <root>content</root>  ")

    then:
    result == true
  }

  def "test isXmlContent with non-XML content"() {
    given:
    def isXmlContentMethod = getIsXmlContentMethod()

    when:
    def jsonResult = isXmlContentMethod.invoke(null, "{\"json\": \"content\"}")
    def textResult = isXmlContentMethod.invoke(null, "plain text")
    def emptyResult = isXmlContentMethod.invoke(null, "")
    def nullResult = isXmlContentMethod.invoke(null, (String) null)

    then:
    jsonResult == false
    textResult == false
    emptyResult == false
    nullResult == false
  }

  def "test parseXmlToWafFormat with valid XML"() {
    given:
    def parseXmlToWafFormatMethod = getParseXmlToWafFormatMethod()
    def xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
<users>
  <user id="1" active="true">
    <name>Alice</name>
    <email>alice@example.com</email>
  </user>
</users>"""

    when:
    def result = parseXmlToWafFormatMethod.invoke(null, xmlContent)

    then:
    result != null
    result instanceof List
    ((List) result).size() == 1

    def rootElement = ((List) result).get(0)
    rootElement instanceof Map
    def rootMap = (Map) rootElement
    rootMap.containsKey("attributes") || rootMap.containsKey("children")
  }

  def "test parseXmlToWafFormat with XML attributes"() {
    given:
    def parseXmlToWafFormatMethod = getParseXmlToWafFormatMethod()
    def xmlContent = '<product id="123" category="electronics">Laptop</product>'

    when:
    def result = parseXmlToWafFormatMethod.invoke(null, xmlContent)

    then:
    result != null
    result instanceof List
    ((List) result).size() == 1

    def rootElement = ((List) result).get(0)
    rootElement instanceof Map
    def rootMap = (Map) rootElement

    // Should have attributes
    rootMap.containsKey("attributes")
    def attributes = rootMap.get("attributes")
    attributes instanceof Map
    ((Map) attributes).get("id") == "123"
    ((Map) attributes).get("category") == "electronics"
  }

  def "test parseXmlToWafFormat with nested XML elements"() {
    given:
    def parseXmlToWafFormatMethod = getParseXmlToWafFormatMethod()
    def xmlContent = """<order>
  <customer>
    <name>John Doe</name>
    <address>
      <street>123 Main St</street>
      <city>Anytown</city>
    </address>
  </customer>
  <items>
    <item>Book</item>
    <item>Pen</item>
  </items>
</order>"""

    when:
    def result = parseXmlToWafFormatMethod.invoke(null, xmlContent)

    then:
    result != null
    result instanceof List
    ((List) result).size() == 1

    def rootElement = ((List) result).get(0)
    rootElement instanceof Map
    def rootMap = (Map) rootElement

    // Should have children
    rootMap.containsKey("children")
    def children = rootMap.get("children")
    children instanceof List
    ((List) children).size() > 0
  }

  def "test parseXmlToWafFormat with invalid XML"() {
    given:
    def parseXmlToWafFormatMethod = getParseXmlToWafFormatMethod()
    def invalidXml = "<unclosed>tag"

    when:
    def result = parseXmlToWafFormatMethod.invoke(null, invalidXml)

    then:
    result == null
  }

  def "test parseXmlToWafFormat with empty content"() {
    given:
    def parseXmlToWafFormatMethod = getParseXmlToWafFormatMethod()

    when:
    def emptyResult = parseXmlToWafFormatMethod.invoke(null, "")
    def nullResult = parseXmlToWafFormatMethod.invoke(null, (String) null)
    def whitespaceResult = parseXmlToWafFormatMethod.invoke(null, "   ")

    then:
    emptyResult == null
    nullResult == null
    whitespaceResult == null
  }

  def "test processObjectForWaf with XML string"() {
    given:
    def processObjectForWafMethod = getProcessObjectForWafMethod()
    def xmlContent = "<message>Hello World</message>"

    when:
    def result = processObjectForWafMethod.invoke(null, xmlContent)

    then:
    result != null
    result instanceof List
    ((List) result).size() == 1
  }

  def "test processObjectForWaf with non-XML string"() {
    given:
    def processObjectForWafMethod = getProcessObjectForWafMethod()
    def jsonContent = "{\"message\": \"Hello World\"}"

    when:
    def result = processObjectForWafMethod.invoke(null, jsonContent)

    then:
    result == jsonContent // Should return original object unchanged
  }

  def "test processObjectForWaf with non-string object"() {
    given:
    def processObjectForWafMethod = getProcessObjectForWafMethod()
    def numberObject = 42

    when:
    def result = processObjectForWafMethod.invoke(null, numberObject)

    then:
    result == numberObject // Should return original object unchanged
  }

  def "test processObjectForWaf with malformed XML fallback"() {
    given:
    def processObjectForWafMethod = getProcessObjectForWafMethod()
    def malformedXml = "<unclosed>tag"

    when:
    def result = processObjectForWafMethod.invoke(null, malformedXml)

    then:
    result == malformedXml // Should fallback to original string when parsing fails
  }

  def "test convertW3cNode with complex XML structure"() {
    given:
    def parseXmlToWafFormatMethod = getParseXmlToWafFormatMethod()
    def xmlContent = """<catalog>
  <book isbn="978-0134685991" available="true">
    <title>Effective Java</title>
    <author>Joshua Bloch</author>
    <price currency="USD">45.99</price>
    <tags>
      <tag>Programming</tag>
      <tag>Java</tag>
      <tag>Best Practices</tag>
    </tags>
  </book>
</catalog>"""

    when:
    def result = parseXmlToWafFormatMethod.invoke(null, xmlContent)

    then:
    result != null
    result instanceof List
    ((List) result).size() == 1

    def rootElement = ((List) result).get(0)
    rootElement instanceof Map
    def rootMap = (Map) rootElement

    // Verify structure contains expected elements
    rootMap.containsKey("children")
    def children = rootMap.get("children")
    children instanceof List

    // The structure should be properly nested
    ((List) children).size() > 0
  }

  def "test XML security - XXE prevention"() {
    given:
    def parseXmlToWafFormatMethod = getParseXmlToWafFormatMethod()
    // XML with DOCTYPE declaration (should be blocked)
    def xxeXml = """<?xml version="1.0"?>
<!DOCTYPE root [
  <!ENTITY xxe SYSTEM "file:///etc/passwd">
]>
<root>&xxe;</root>"""

    when:
    def result = parseXmlToWafFormatMethod.invoke(null, xxeXml)

    then:
    // Should return null due to XXE protection (DOCTYPE disabled)
    result == null
  }

  def "test XML with text content only"() {
    given:
    def parseXmlToWafFormatMethod = getParseXmlToWafFormatMethod()
    def xmlContent = "<message>Simple text content</message>"

    when:
    def result = parseXmlToWafFormatMethod.invoke(null, xmlContent)

    then:
    result != null
    result instanceof List
    ((List) result).size() == 1

    def rootElement = ((List) result).get(0)
    rootElement instanceof Map
    def rootMap = (Map) rootElement

    // Should have children containing the text content
    rootMap.containsKey("children")
    def children = rootMap.get("children")
    children instanceof List
    ((List) children).size() > 0

    // Text content should be preserved
    def textContent = ((List) children).find { it instanceof String }
    textContent == "Simple text content"
  }

  def "test XML recursion depth limit"() {
    given:
    def parseXmlToWafFormatMethod = getParseXmlToWafFormatMethod()
    // Create deeply nested XML
    def deepXml = "<level1><level2><level3><level4><level5><level6><level7><level8><level9><level10>Deep content</level10></level9></level8></level7></level6></level5></level4></level3></level2></level1>"

    when:
    def result = parseXmlToWafFormatMethod.invoke(null, deepXml)

    then:
    result != null
    result instanceof List
    // Should handle deep nesting gracefully (may truncate at max depth)
    ((List) result).size() == 1
  }

  // Helper methods to access private static methods via reflection
  private Method getIsXmlContentMethod() {
    def method = WafPublishingBodyHandler.getDeclaredMethod("isXmlContent", String)
    method.setAccessible(true)
    return method
  }

  private Method getParseXmlToWafFormatMethod() {
    def method = WafPublishingBodyHandler.getDeclaredMethod("parseXmlToWafFormat", String)
    method.setAccessible(true)
    return method
  }

  private Method getProcessObjectForWafMethod() {
    def method = WafPublishingBodyHandler.getDeclaredMethod("processObjectForWaf", Object)
    method.setAccessible(true)
    return method
  }
}
