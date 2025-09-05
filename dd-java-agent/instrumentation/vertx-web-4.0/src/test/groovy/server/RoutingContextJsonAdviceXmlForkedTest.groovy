package server

import datadog.trace.instrumentation.vertx_4_0.server.RoutingContextJsonAdvice
import spock.lang.Specification

import java.lang.reflect.Method

class RoutingContextJsonAdviceXmlForkedTest extends Specification {

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

    expect:
    isXmlContentMethod.invoke(null, "{\"json\": \"content\"}") == false
    isXmlContentMethod.invoke(null, "plain text") == false
    isXmlContentMethod.invoke(null, "") == false
    isXmlContentMethod.invoke(null, null) == false
  }

  def "test isXmlContent with incomplete XML"() {
    given:
    def isXmlContentMethod = getIsXmlContentMethod()

    expect:
    isXmlContentMethod.invoke(null, "<incomplete") == false
    isXmlContentMethod.invoke(null, "incomplete>") == false
  }

  def "test parseXmlToWafFormat with simple XML"() {
    given:
    def parseXmlMethod = getParseXmlToWafFormatMethod()
    def xmlContent = "<user>John</user>"

    when:
    def result = parseXmlMethod.invoke(null, xmlContent)

    then:
    result instanceof List
    def resultList = (List) result
    resultList.size() == 1
    def rootElement = resultList[0]
    rootElement instanceof Map
    def rootMap = (Map) rootElement
    rootMap.containsKey("children")
    def children = (List) rootMap.get("children")
    children.contains("John")
  }

  def "test parseXmlToWafFormat with attributes"() {
    given:
    def parseXmlMethod = getParseXmlToWafFormatMethod()
    def xmlContent = '<user id="123" active="true">John</user>'

    when:
    def result = parseXmlMethod.invoke(null, xmlContent)

    then:
    result instanceof List
    def resultList = (List) result
    resultList.size() == 1
    def rootElement = resultList[0]
    rootElement instanceof Map
    def rootMap = (Map) rootElement

    // Check attributes
    rootMap.containsKey("attributes")
    def attributes = (Map) rootMap.get("attributes")
    attributes.get("id") == "123"
    attributes.get("active") == "true"

    // Check children (text content)
    rootMap.containsKey("children")
    def children = (List) rootMap.get("children")
    children.contains("John")
  }

  def "test parseXmlToWafFormat with nested elements"() {
    given:
    def parseXmlMethod = getParseXmlToWafFormatMethod()
    def xmlContent = '''
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

    when:
    def result = parseXmlMethod.invoke(null, xmlContent)

    then:
    result instanceof List
    def resultList = (List) result
    resultList.size() == 1
    def rootElement = resultList[0]
    rootElement instanceof Map
    def rootMap = (Map) rootElement
    rootMap.containsKey("children")

    def children = (List) rootMap.get("children")
    // Should have user elements (ignoring whitespace text nodes)
    def userElements = children.findAll { it instanceof Map }
    userElements.size() >= 2

    // Check first user element
    def firstUser = userElements[0] as Map
    firstUser.containsKey("attributes")
    def firstUserAttrs = firstUser.get("attributes") as Map
    firstUserAttrs.get("id") == "1"
  }

  def "test parseXmlToWafFormat with empty/null content"() {
    given:
    def parseXmlMethod = getParseXmlToWafFormatMethod()

    expect:
    parseXmlMethod.invoke(null, "") == null
    parseXmlMethod.invoke(null, "   ") == null
    parseXmlMethod.invoke(null, null) == null
  }

  def "test parseXmlToWafFormat with invalid XML"() {
    given:
    def parseXmlMethod = getParseXmlToWafFormatMethod()
    def invalidXml = "<user><name>John</invalid>"

    when:
    def result = parseXmlMethod.invoke(null, invalidXml)

    then:
    // Should return null on parsing error
    result == null
  }

  def "test parseXmlToWafFormat with complex XML"() {
    given:
    def parseXmlMethod = getParseXmlToWafFormatMethod()
    def xmlContent = '''
      <?xml version="1.0" encoding="UTF-8"?>
      <users>
        <user id="1" active="true">
          <name>Alice</name>
          <email>alice@example.com</email>
          <profile>
            <age>30</age>
            <city>New York</city>
          </profile>
        </user>
      </users>
    '''

    when:
    def result = parseXmlMethod.invoke(null, xmlContent)

    then:
    result instanceof List
    def resultList = (List) result
    resultList.size() == 1
    def rootElement = resultList[0]
    rootElement instanceof Map
    def rootMap = (Map) rootElement
    rootMap.containsKey("children")

    // Should have nested structure
    def children = (List) rootMap.get("children")
    def userElements = children.findAll { it instanceof Map }
    userElements.size() >= 1

    def firstUser = userElements[0] as Map
    firstUser.containsKey("children")
    def userChildren = (List) firstUser.get("children")

    // Should have profile nested element
    def profileElements = userChildren.findAll {
      it instanceof Map && ((Map) it).containsKey("children")
    }
    profileElements.size() >= 1
  }

  def "test parseXmlToWafFormat with empty element"() {
    given:
    def parseXmlMethod = getParseXmlToWafFormatMethod()
    def xmlContent = "<empty/>"

    when:
    def result = parseXmlMethod.invoke(null, xmlContent)

    then:
    result instanceof List
    def resultList = (List) result
    resultList.size() == 1
    def rootElement = resultList[0]
    rootElement instanceof Map
    def rootMap = (Map) rootElement

    // Empty element should have empty or no children/attributes
    !rootMap.containsKey("attributes") || ((Map) rootMap.get("attributes")).isEmpty()
    !rootMap.containsKey("children") || ((List) rootMap.get("children")).isEmpty()
  }

  def "test processObjectForWaf with XML content"() {
    given:
    def processObjectMethod = getProcessObjectForWafMethod()
    def xmlContent = '<item attr="value">text content</item>'

    when:
    def result = processObjectMethod.invoke(null, xmlContent)

    then:
    result instanceof List
    def resultList = (List) result
    def rootElement = resultList[0] as Map

    // Verify attributes are preserved
    rootElement.containsKey("attributes")
    def attributes = rootElement.get("attributes") as Map
    attributes.get("attr") == "value"

    // Verify text content is preserved
    rootElement.containsKey("children")
    def children = rootElement.get("children") as List
    children.contains("text content")
  }

  def "test processObjectForWaf with non-XML content"() {
    given:
    def processObjectMethod = getProcessObjectForWafMethod()
    def jsonContent = '{"key": "value"}'

    when:
    def result = processObjectMethod.invoke(null, jsonContent)

    then:
    // Should return original object unchanged
    result == jsonContent
  }

  def "test processObjectForWaf with non-String object"() {
    given:
    def processObjectMethod = getProcessObjectForWafMethod()
    def numberObject = 123

    when:
    def result = processObjectMethod.invoke(null, numberObject)

    then:
    // Should return original object unchanged
    result == numberObject
  }

  def "test convertW3cNode method via parseXmlToWafFormat"() {
    given:
    def parseXmlMethod = getParseXmlToWafFormatMethod()
    def xmlContent = '<item attr1="value1" attr2="value2">text content</item>'

    when:
    def result = parseXmlMethod.invoke(null, xmlContent)

    then:
    result instanceof List
    def resultList = (List) result
    def rootElement = resultList[0] as Map

    // Verify attributes are preserved
    rootElement.containsKey("attributes")
    def attributes = rootElement.get("attributes") as Map
    attributes.size() == 2
    attributes.get("attr1") == "value1"
    attributes.get("attr2") == "value2"

    // Verify text content is preserved
    rootElement.containsKey("children")
    def children = rootElement.get("children") as List
    children.contains("text content")
  }

  def "test XML security - XXE prevention"() {
    given:
    def parseXmlMethod = getParseXmlToWafFormatMethod()
    def xmlWithDoctype = '''<?xml version="1.0"?>
      <!DOCTYPE root [
        <!ENTITY xxe SYSTEM "file:///etc/passwd">
      ]>
      <root>&xxe;</root>'''

    when:
    def result = parseXmlMethod.invoke(null, xmlWithDoctype)

    then:
    // Should return null due to XXE prevention (DOCTYPE disabled)
    result == null
  }

  // Helper methods to access private methods via reflection
  private Method getIsXmlContentMethod() {
    Method method = RoutingContextJsonAdvice.getDeclaredMethod("isXmlContent", String)
    method.setAccessible(true)
    return method
  }

  private Method getParseXmlToWafFormatMethod() {
    Method method = RoutingContextJsonAdvice.getDeclaredMethod("parseXmlToWafFormat", String)
    method.setAccessible(true)
    return method
  }

  private Method getProcessObjectForWafMethod() {
    Method method = RoutingContextJsonAdvice.getDeclaredMethod("processObjectForWaf", Object)
    method.setAccessible(true)
    return method
  }
}
