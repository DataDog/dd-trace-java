package datadog.trace.bootstrap.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

class XmlDomUtilsTest {

  private static final int MAX_RECURSION = 15;

  @Test
  void testIsXmlContent_withXmlDeclaration() {
    assertTrue(XmlDomUtils.isXmlContent("<?xml version=\"1.0\"?><root></root>"));
    assertTrue(XmlDomUtils.isXmlContent("  <?xml version=\"1.0\"?>  <root></root>  "));
  }

  @Test
  void testIsXmlContent_withXmlElements() {
    assertTrue(XmlDomUtils.isXmlContent("<root><child>text</child></root>"));
    assertTrue(XmlDomUtils.isXmlContent("  <root><child>text</child></root>  "));
    assertTrue(XmlDomUtils.isXmlContent("<person><name>John</name><age>30</age></person>"));
  }

  @Test
  void testIsXmlContent_withSelfClosingTags() {
    assertTrue(XmlDomUtils.isXmlContent("<root/>"));
    assertTrue(XmlDomUtils.isXmlContent("<person name=\"John\" age=\"30\"/>"));
  }

  @Test
  void testIsXmlContent_excludesJson() {
    assertFalse(XmlDomUtils.isXmlContent("{\"name\": \"John\", \"age\": 30}"));
    assertFalse(XmlDomUtils.isXmlContent("[{\"name\": \"John\"}, {\"name\": \"Jane\"}]"));
    assertFalse(XmlDomUtils.isXmlContent("  {\"key\": \"value\"}  "));
  }

  @Test
  void testIsXmlContent_withInvalidXml() {
    assertFalse(XmlDomUtils.isXmlContent("<root>unclosed tag"));
    assertFalse(XmlDomUtils.isXmlContent("plain text"));
    assertFalse(XmlDomUtils.isXmlContent(""));
    assertFalse(XmlDomUtils.isXmlContent(null));
    assertFalse(XmlDomUtils.isXmlContent("   "));
  }

  @Test
  void testConvertW3cNode_withSimpleElement() throws Exception {
    String xmlContent = "<person><name>John</name><age>30</age></person>";
    Document doc = parseXmlString(xmlContent);
    Element root = doc.getDocumentElement();

    Object result = XmlDomUtils.processXmlForWaf(root, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> rootMap = (Map<String, Object>) result;
    assertTrue(rootMap.containsKey("children"));

    @SuppressWarnings("unchecked")
    List<Object> children = (List<Object>) rootMap.get("children");
    assertEquals(2, children.size());
  }

  @Test
  void testConvertW3cNode_withAttributes() throws Exception {
    String xmlContent = "<person name=\"John\" age=\"30\"><city>New York</city></person>";
    Document doc = parseXmlString(xmlContent);
    Element root = doc.getDocumentElement();

    Object result = XmlDomUtils.processXmlForWaf(root, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> rootMap = (Map<String, Object>) result;

    // Check attributes
    assertTrue(rootMap.containsKey("attributes"));
    @SuppressWarnings("unchecked")
    Map<String, String> attributes = (Map<String, String>) rootMap.get("attributes");
    assertEquals("John", attributes.get("name"));
    assertEquals("30", attributes.get("age"));

    // Check children
    assertTrue(rootMap.containsKey("children"));
    @SuppressWarnings("unchecked")
    List<Object> children = (List<Object>) rootMap.get("children");
    assertEquals(1, children.size());
  }

  @Test
  void testConvertW3cNode_withTextContent() throws Exception {
    String xmlContent = "<message>Hello World</message>";
    Document doc = parseXmlString(xmlContent);
    Element root = doc.getDocumentElement();

    Object result = XmlDomUtils.processXmlForWaf(root, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> rootMap = (Map<String, Object>) result;
    assertTrue(rootMap.containsKey("children"));

    @SuppressWarnings("unchecked")
    List<Object> children = (List<Object>) rootMap.get("children");
    assertEquals(1, children.size());
    assertEquals("Hello World", children.get(0));
  }

  @Test
  void testConvertW3cNode_withMaxRecursionLimit() throws Exception {
    String xmlContent = "<root><level1><level2><level3>deep</level3></level2></level1></root>";
    Document doc = parseXmlString(xmlContent);
    Element root = doc.getDocumentElement();

    // Test with very low recursion limit
    Object result = XmlDomUtils.processXmlForWaf(root, 1);
    assertNotNull(result);

    // Test with zero recursion
    Object zeroResult = XmlDomUtils.processXmlForWaf(root, 0);
    assertNull(zeroResult);

    // Test with null node
    Object nullResult = XmlDomUtils.processXmlForWaf(null, MAX_RECURSION);
    assertNull(nullResult);
  }

  @Test
  void testConvertDocument() throws Exception {
    String xmlContent = "<?xml version=\"1.0\"?><root><child>content</child></root>";
    Document doc = parseXmlString(xmlContent);

    Object result = XmlDomUtils.convertDocument(doc, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> resultMap = (Map<String, Object>) result;
    assertTrue(resultMap.containsKey("children"));
  }

  @Test
  void testConvertDocument_withNullDocument() {
    Object result = XmlDomUtils.convertDocument(null, MAX_RECURSION);
    assertNull(result);
  }

  @Test
  void testConvertElement() throws Exception {
    String xmlContent = "<person><name>John</name></person>";
    Document doc = parseXmlString(xmlContent);
    Element element = doc.getDocumentElement();

    Object result = XmlDomUtils.processXmlForWaf(element, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> resultMap = (Map<String, Object>) result;
    assertTrue(resultMap.containsKey("children"));
  }

  @Test
  void testConvertElement_withNullElement() {
    Object result = XmlDomUtils.processXmlForWaf((Element) null, MAX_RECURSION);
    assertNull(result);
  }

  @Test
  void testProcessXmlForWaf_withDocument() throws Exception {
    String xmlContent = "<?xml version=\"1.0\"?><root><child>content</child></root>";
    Document doc = parseXmlString(xmlContent);

    Object result = XmlDomUtils.processXmlForWaf(doc, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);
  }

  @Test
  void testProcessXmlForWaf_withElement() throws Exception {
    String xmlContent = "<person><name>John</name></person>";
    Document doc = parseXmlString(xmlContent);
    Element element = doc.getDocumentElement();

    Object result = XmlDomUtils.processXmlForWaf(element, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);
  }

  @Test
  void testProcessXmlForWaf_withXmlString() {
    String xmlContent = "<person><name>John</name><age>30</age></person>";

    Object result = XmlDomUtils.processXmlForWaf(xmlContent, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> resultMap = (Map<String, Object>) result;
    assertTrue(resultMap.containsKey("children"));
  }

  @Test
  void testProcessXmlForWaf_withNonXmlString() {
    String jsonContent = "{\"name\": \"John\", \"age\": 30}";

    Object result = XmlDomUtils.processXmlForWaf(jsonContent, MAX_RECURSION);

    assertNull(result);
  }

  @Test
  void testProcessXmlForWaf_withNullInput() {
    Object result = XmlDomUtils.processXmlForWaf(null, MAX_RECURSION);
    assertNull(result);
  }

  @Test
  void testParseXmlStringToWafFormat_validXml() throws Exception {
    String xmlContent = "<book><title>Java Guide</title><author>John Doe</author></book>";

    Object result = XmlDomUtils.processXmlForWaf(xmlContent, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> resultMap = (Map<String, Object>) result;
    assertTrue(resultMap.containsKey("children"));

    @SuppressWarnings("unchecked")
    List<Object> children = (List<Object>) resultMap.get("children");
    assertEquals(2, children.size()); // title and author elements
  }

  @Test
  void testParseXmlStringToWafFormat_invalidXml() {
    String invalidXml = "<root><unclosed>";

    // Public API returns null for invalid XML instead of throwing exception
    Object result = XmlDomUtils.handleXmlString(invalidXml, MAX_RECURSION);
    assertNull(result);
  }

  @Test
  void testParseXmlStringToWafFormat_emptyString() throws Exception {
    Object result = XmlDomUtils.handleXmlString("", MAX_RECURSION);
    assertNull(result);

    Object nullResult = XmlDomUtils.handleXmlString(null, MAX_RECURSION);
    assertNull(nullResult);
  }

  @Test
  void testHandleXmlString_validXml() {
    String xmlContent = "<note><to>Tove</to><from>Jani</from><body>Don't forget me!</body></note>";

    Object result = XmlDomUtils.handleXmlString(xmlContent, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);
  }

  @Test
  void testHandleXmlString_invalidXml() {
    String invalidXml = "<root><unclosed>";

    Object result = XmlDomUtils.handleXmlString(invalidXml, MAX_RECURSION);

    // Should return null when parsing fails, not throw exception
    assertNull(result);
  }

  @Test
  void testHandleXmlString_nullAndEmpty() {
    assertNull(XmlDomUtils.handleXmlString(null, MAX_RECURSION));
    assertNull(XmlDomUtils.handleXmlString("", MAX_RECURSION));
    assertNull(XmlDomUtils.handleXmlString("   ", MAX_RECURSION));
  }

  @Test
  void testXmlWithNamespaces() throws Exception {
    String xmlContent =
        "<?xml version=\"1.0\"?>"
            + "<root xmlns:ns=\"http://example.com/ns\">"
            + "<ns:element>content</ns:element>"
            + "</root>";

    Object result = XmlDomUtils.processXmlForWaf(xmlContent, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);
  }

  @Test
  void testXmlWithCDATA() throws Exception {
    String xmlContent = "<root><data><![CDATA[Some <b>bold</b> text]]></data></root>";

    Object result = XmlDomUtils.processXmlForWaf(xmlContent, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);
  }

  @Test
  void testComplexXmlStructure() throws Exception {
    String xmlContent =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<library>"
            + "  <book id=\"1\" category=\"fiction\">"
            + "    <title>The Great Gatsby</title>"
            + "    <author>"
            + "      <first>F. Scott</first>"
            + "      <last>Fitzgerald</last>"
            + "    </author>"
            + "    <year>1925</year>"
            + "  </book>"
            + "  <book id=\"2\" category=\"science\">"
            + "    <title>A Brief History of Time</title>"
            + "    <author>"
            + "      <first>Stephen</first>"
            + "      <last>Hawking</last>"
            + "    </author>"
            + "    <year>1988</year>"
            + "  </book>"
            + "</library>";

    Object result = XmlDomUtils.processXmlForWaf(xmlContent, MAX_RECURSION);

    assertNotNull(result);
    assertTrue(result instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> resultMap = (Map<String, Object>) result;
    assertTrue(resultMap.containsKey("children"));

    @SuppressWarnings("unchecked")
    List<Object> children = (List<Object>) resultMap.get("children");
    // Should have 2 book elements (ignoring whitespace text nodes)
    long bookElements = children.stream().filter(child -> child instanceof Map).count();
    assertEquals(2, bookElements);
  }

  @Test
  void testXmlSecurityFeatures() throws Exception {
    // Test that XXE prevention is working - this should not cause security issues
    String xmlWithDTD =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE root ["
            + "<!ENTITY test \"test value\">"
            + "]>"
            + "<root>&test;</root>";

    // Public API returns null for XML with DTD due to security restrictions
    Object result = XmlDomUtils.handleXmlString(xmlWithDTD, MAX_RECURSION);
    assertNull(result);
  }

  // Helper method to parse XML string into Document
  private Document parseXmlString(String xmlContent) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(new InputSource(new StringReader(xmlContent)));
  }
}
