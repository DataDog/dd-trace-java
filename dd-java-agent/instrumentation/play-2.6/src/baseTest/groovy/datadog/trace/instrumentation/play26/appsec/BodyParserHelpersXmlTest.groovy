package datadog.trace.instrumentation.play26.appsec

import org.w3c.dom.Document
import spock.lang.Specification

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import java.lang.reflect.Method

class BodyParserHelpersXmlTest extends Specification {

  def "test convertW3cNode with simple element"() {
    given:
    def xml = "<user>John</user>"
    def document = parseXml(xml)
    def element = document.getDocumentElement()
    def convertMethod = getConvertW3cNodeMethod()

    when:
    def result = convertMethod.invoke(null, element, 10)

    then:
    result instanceof Map
    def resultMap = (Map) result
    resultMap.containsKey("children")
    def children = (List) resultMap.get("children")
    children.size() == 1
    children[0] == "John"
  }

  def "test convertW3cNode with attributes"() {
    given:
    def xml = '<user id="123" active="true">John</user>'
    def document = parseXml(xml)
    def element = document.getDocumentElement()
    def convertMethod = getConvertW3cNodeMethod()

    when:
    def result = convertMethod.invoke(null, element, 10)

    then:
    result instanceof Map
    def resultMap = (Map) result
    resultMap.containsKey("attributes")
    def attributes = (Map) resultMap.get("attributes")
    attributes.get("id") == "123"
    attributes.get("active") == "true"

    resultMap.containsKey("children")
    def children = (List) resultMap.get("children")
    children.size() == 1
    children[0] == "John"
  }

  def "test convertW3cNode with nested elements"() {
    given:
    def xml = '''
      <users>
        <user id="1">
          <name>Alice</name>
          <email>alice@example.com</email>
        </user>
        <user id="2">
          <name>Bob</name>
          <email>bob@example.com</email>
        </user>
      </users>
    '''
    def document = parseXml(xml)
    def element = document.getDocumentElement()
    def convertMethod = getConvertW3cNodeMethod()

    when:
    def result = convertMethod.invoke(null, element, 10)

    then:
    result instanceof Map
    def resultMap = (Map) result
    resultMap.containsKey("children")
    def children = (List) resultMap.get("children")

    // Should have user elements (ignoring whitespace text nodes)
    def userElements = children.findAll { it instanceof Map }
    userElements.size() >= 2

    // Check first user element
    def firstUser = userElements[0] as Map
    firstUser.containsKey("attributes")
    def firstUserAttrs = firstUser.get("attributes") as Map
    firstUserAttrs.get("id") == "1"
  }

  def "test convertW3cNode with empty element"() {
    given:
    def xml = "<empty/>"
    def document = parseXml(xml)
    def element = document.getDocumentElement()
    def convertMethod = getConvertW3cNodeMethod()

    when:
    def result = convertMethod.invoke(null, element, 10)

    then:
    result instanceof Map
    def resultMap = (Map) result
    // Empty element should have empty or no children/attributes
    !resultMap.containsKey("attributes") || ((Map) resultMap.get("attributes")).isEmpty()
    !resultMap.containsKey("children") || ((List) resultMap.get("children")).isEmpty()
  }

  def "test convertW3cNode with recursion limit"() {
    given:
    def xml = "<level1><level2><level3><level4>deep</level4></level3></level2></level1>"
    def document = parseXml(xml)
    def element = document.getDocumentElement()
    def convertMethod = getConvertW3cNodeMethod()

    when:
    def result = convertMethod.invoke(null, element, 2) // Limited recursion

    then:
    result instanceof Map
    def resultMap = (Map) result
    resultMap.containsKey("children")

    // Should stop at recursion limit
    def children = (List) resultMap.get("children")
    def level2 = children.find { it instanceof Map } as Map
    level2 != null
    level2.containsKey("children")

    // Level 3 should be null due to recursion limit
    def level2Children = (List) level2.get("children")
    def level3 = level2Children.find { it instanceof Map }
    level3 == null // Should be truncated due to recursion limit
  }

  def "test convertW3cNode with null input"() {
    given:
    def convertMethod = getConvertW3cNodeMethod()

    when:
    def result = convertMethod.invoke(null, null, 10)

    then:
    result == null
  }

  def "test convertW3cNode with zero recursion"() {
    given:
    def xml = "<user>John</user>"
    def document = parseXml(xml)
    def element = document.getDocumentElement()
    def convertMethod = getConvertW3cNodeMethod()

    when:
    def result = convertMethod.invoke(null, element, 0)

    then:
    result == null
  }

  def "test handleXmlDocument with null document"() {
    when:
    BodyParserHelpers.handleXmlDocument(null, "test source")

    then:
    // Should handle null gracefully
    noExceptionThrown()
  }

  // Helper methods
  private Document parseXml(String xml) {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
    // Security settings to prevent XXE attacks
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setExpandEntityReferences(false)

    DocumentBuilder builder = factory.newDocumentBuilder()
    return builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")))
  }

  private Method getConvertW3cNodeMethod() {
    Method method = BodyParserHelpers.getDeclaredMethod("convertW3cNode", org.w3c.dom.Node, int)
    method.setAccessible(true)
    return method
  }
}
