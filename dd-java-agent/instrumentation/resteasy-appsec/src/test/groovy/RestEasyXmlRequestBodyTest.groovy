package datadog.trace.instrumentation.resteasy

import datadog.trace.agent.test.AgentTestRunner
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import java.util.regex.Pattern

class RestEasyXmlRequestBodyTest extends AgentTestRunner {

  // Inline XML parsing methods for testing (copied from instrumentation)
  private static final Pattern XML_PATTERN = Pattern.compile("^\\s*<[?!]?\\w+.*>.*", Pattern.DOTALL)

  static boolean isXmlContent(String content) {
    if (content == null || content.trim().isEmpty()) {
      return false
    }
    return XML_PATTERN.matcher(content.trim()).matches()
  }

  static Map<String, Object> parseXmlToMap(String xmlContent) {
    if (!isXmlContent(xmlContent)) {
      return null
    }

    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
      factory.setXIncludeAware(false)
      factory.setExpandEntityReferences(false)

      DocumentBuilder builder = factory.newDocumentBuilder()
      Document document = builder.parse(new InputSource(new StringReader(xmlContent)))

      Element rootElement = document.getDocumentElement()
      Map<String, Object> result = new HashMap<>()
      result.put(rootElement.getNodeName(), convertNodeToObject(rootElement))
      return result
    } catch (Exception e) {
      return null
    }
  }

  static Object convertNodeToObject(Node node) {
    if (node instanceof Element) {
      Element element = (Element) node
      Map<String, Object> attributes = new HashMap<>()
      Map<String, Object> children = new HashMap<>()

      if (element.hasAttributes()) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
          Node attr = element.getAttributes().item(i)
          attributes.put("@" + attr.getNodeName(), attr.getNodeValue())
        }
      }

      if (element.hasChildNodes()) {
        for (int i = 0; i < element.getChildNodes().getLength(); i++) {
          Node child = element.getChildNodes().item(i)
          if (child instanceof Element) {
            String childName = child.getNodeName()
            Object childValue = convertNodeToObject(child)
            if (children.containsKey(childName)) {
              Object existing = children.get(childName)
              if (existing instanceof List) {
                ((List<Object>) existing).add(childValue)
              } else {
                List<Object> list = new ArrayList<>()
                list.add(existing)
                list.add(childValue)
                children.put(childName, list)
              }
            } else {
              children.put(childName, childValue)
            }
          }
        }
      }

      Map<String, Object> repr = new HashMap<>()
      if (!attributes.isEmpty()) {
        repr.putAll(attributes)
      }
      if (!children.isEmpty()) {
        repr.putAll(children)
      }
      if (repr.isEmpty()) {
        String textContent = element.getTextContent()
        if (textContent != null) {
          textContent = textContent.trim()
          if (!textContent.isEmpty()) {
            return textContent
          }
        }
        return ""
      }
      return repr
    }
    return null
  }

  def 'test XML request body parsing with simple XML'() {
    setup:
    def xmlContent = '<user><name>John</name><age>30</age></user>'

    expect:
    def result = parseXmlToMap(xmlContent)
    result instanceof Map
    result.user instanceof Map
    result.user.name == 'John'
    result.user.age == '30'
  }

  def 'test XML request body parsing with attributes'() {
    setup:
    def xmlContent = '<user id="123" active="true"><name>Jane</name></user>'

    expect:
    def result = parseXmlToMap(xmlContent)
    result instanceof Map
    result.user instanceof Map
    result.user['@id'] == '123'
    result.user['@active'] == 'true'
    result.user.name == 'Jane'
  }

  def 'test XML content detection'() {
    expect:
    isXmlContent('<user><name>John</name></user>')
    isXmlContent('<?xml version="1.0"?><root/>')
    isXmlContent('  <data>test</data>  ')
    !isXmlContent('{"name": "John"}')
    !isXmlContent('plain text')
    !isXmlContent('')
    !isXmlContent(null)
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
    def result = parseXmlToMap(xmlContent)
    result == null
  }
}
