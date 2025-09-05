package com.datadog.appsec.event.data;

import static com.datadog.appsec.ddwaf.WAFModule.MAX_DEPTH;
import static com.datadog.appsec.ddwaf.WAFModule.MAX_ELEMENTS;
import static com.datadog.appsec.ddwaf.WAFModule.MAX_STRING_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.appsec.gateway.AppSecRequestContext;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

public class ObjectIntrospectionXmlTest {

  private static final DocumentBuilderFactory FACTORY = DocumentBuilderFactory.newInstance();
  private AppSecRequestContext ctx;

  @BeforeEach
  void setUp() {
    ctx = new AppSecRequestContext();
  }

  private Document parseXml(String xmlContent) throws Exception {
    DocumentBuilder builder = FACTORY.newDocumentBuilder();
    return builder.parse(new InputSource(new StringReader(xmlContent)));
  }

  private Element parseXmlElement(String xmlContent) throws Exception {
    return parseXml(xmlContent).getDocumentElement();
  }

  @Test
  void testXmlNodeTypesComprehensiveCoverage() throws Exception {
    // Test null document
    Object result = ObjectIntrospection.convert(null, ctx);
    assertNull(result);

    // Test empty document
    Document emptyDoc = parseXml("<?xml version=\"1.0\"?><empty/>");
    result = ObjectIntrospection.convert(emptyDoc, ctx);
    assertInstanceOf(Map.class, result);

    // Test simple element with text
    Element textElement = parseXmlElement("<?xml version=\"1.0\"?><text>hello</text>");
    result = ObjectIntrospection.convert(textElement, ctx);
    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> textMap = (Map<String, Object>) result;
    assertEquals("hello", textMap.get("_text"));

    // Test element with attributes
    Element attrElement =
        parseXmlElement("<?xml version=\"1.0\"?><item id=\"123\" type=\"test\"/>");
    result = ObjectIntrospection.convert(attrElement, ctx);
    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> attrMap = (Map<String, Object>) result;
    assertEquals("123", attrMap.get("id"));
    assertEquals("test", attrMap.get("type"));
  }

  @Test
  void testXmlNestedStructures() throws Exception {
    // Test deeply nested structure
    String nestedXml = "<?xml version=\"1.0\"?>" + "<a><b><c>123</c></b></a>";
    Element nestedElement = parseXmlElement(nestedXml);
    Object result = ObjectIntrospection.convert(nestedElement, ctx);

    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> rootMap = (Map<String, Object>) result;
    assertTrue(rootMap.containsKey("b"));

    @SuppressWarnings("unchecked")
    Map<String, Object> bMap = (Map<String, Object>) rootMap.get("b");
    assertTrue(bMap.containsKey("c"));

    @SuppressWarnings("unchecked")
    Map<String, Object> cMap = (Map<String, Object>) bMap.get("c");
    assertEquals("123", cMap.get("_text"));

    // Test array-like structure with multiple elements
    String arrayXml =
        "<?xml version=\"1.0\"?>"
            + "<root>"
            + "  <item>1</item>"
            + "  <item>2</item>"
            + "  <item>3</item>"
            + "</root>";
    Element arrayElement = parseXmlElement(arrayXml);
    result = ObjectIntrospection.convert(arrayElement, ctx);

    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> arrayMap = (Map<String, Object>) result;
    assertTrue(arrayMap.containsKey("item"));
    assertInstanceOf(List.class, arrayMap.get("item"));

    @SuppressWarnings("unchecked")
    List<Object> items = (List<Object>) arrayMap.get("item");
    assertEquals(3, items.size());
  }

  @Test
  void testXmlEdgeCases() throws Exception {
    // Test empty element
    Element emptyElement = parseXmlElement("<?xml version=\"1.0\"?><empty/>");
    Object result = ObjectIntrospection.convert(emptyElement, ctx);
    assertInstanceOf(Map.class, result);

    // Test element with only whitespace
    Element whitespaceElement =
        parseXmlElement("<?xml version=\"1.0\"?><whitespace>   </whitespace>");
    result = ObjectIntrospection.convert(whitespaceElement, ctx);
    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> whitespaceMap = (Map<String, Object>) result;
    assertEquals(null, whitespaceMap.get("_text"));

    // Test element with mixed content (text and child elements)
    String mixedXml =
        "<?xml version=\"1.0\"?>" + "<mixed>Text before<child>child text</child>Text after</mixed>";
    Element mixedElement = parseXmlElement(mixedXml);
    result = ObjectIntrospection.convert(mixedElement, ctx);
    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> mixedMap = (Map<String, Object>) result;
    assertTrue(mixedMap.containsKey("child"));
    assertTrue(mixedMap.containsKey("_text"));

    // Test element with special characters
    String specialXml =
        "<?xml version=\"1.0\"?>"
            + "<special>&lt;test&gt; &amp; \"quotes\" 'apostrophes'</special>";
    Element specialElement = parseXmlElement(specialXml);
    result = ObjectIntrospection.convert(specialElement, ctx);
    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> specialMap = (Map<String, Object>) result;
    assertEquals("<test> & \"quotes\" 'apostrophes'", specialMap.get("_text"));
  }

  @Test
  void testXmlStringTruncation() throws Exception {
    // Create XML with very long text content
    StringBuilder longTextBuilder = new StringBuilder();
    for (int i = 0; i < MAX_STRING_SIZE + 100; i++) {
      longTextBuilder.append("A");
    }
    String longText = longTextBuilder.toString();
    String longXml = "<?xml version=\"1.0\"?><long>" + longText + "</long>";
    Element longElement = parseXmlElement(longXml);

    Object result = ObjectIntrospection.convert(longElement, ctx);

    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> longMap = (Map<String, Object>) result;
    String truncatedText = (String) longMap.get("_text");
    assertTrue(truncatedText.length() <= MAX_STRING_SIZE);

    // Verify that truncation occurred by checking the context was marked as truncated
    assertTrue(ctx.isWafTruncated());
  }

  @Test
  void testXmlWithDeepNestingTriggersDepthLimit() throws Exception {
    // Create deeply nested XML beyond MAX_DEPTH
    StringBuilder deepXml = new StringBuilder("<?xml version=\"1.0\"?>");
    for (int i = 0; i <= MAX_DEPTH + 2; i++) {
      deepXml.append("<level").append(i).append(">");
    }
    deepXml.append("deep content");
    for (int i = MAX_DEPTH + 2; i >= 0; i--) {
      deepXml.append("</level").append(i).append(">");
    }

    Element deepElement = parseXmlElement(deepXml.toString());
    Object result = ObjectIntrospection.convert(deepElement, ctx);

    assertInstanceOf(Map.class, result);

    // Verify truncation was triggered by checking the context
    assertTrue(ctx.isWafTruncated());

    // Count actual nesting depth in result
    int depth = countNesting((Map<String, Object>) result, 0);
    assertTrue(depth <= MAX_DEPTH);
  }

  @Test
  void testXmlWithLargeNumberOfElementsTriggersElementLimit() throws Exception {
    // Create XML with many child elements
    StringBuilder largeXml = new StringBuilder("<?xml version=\"1.0\"?><root>");
    for (int i = 0; i <= MAX_ELEMENTS + 10; i++) {
      largeXml.append("<item>").append(i).append("</item>");
    }
    largeXml.append("</root>");

    Element largeElement = parseXmlElement(largeXml.toString());
    Object result = ObjectIntrospection.convert(largeElement, ctx);

    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> largeMap = (Map<String, Object>) result;

    if (largeMap.containsKey("item")) {
      Object items = largeMap.get("item");
      if (items instanceof List) {
        @SuppressWarnings("unchecked")
        List<Object> itemList = (List<Object>) items;
        assertTrue(itemList.size() <= MAX_ELEMENTS);
      }
    }

    // Verify truncation was triggered by checking the context
    assertTrue(ctx.isWafTruncated());
  }

  @Test
  void testXmlAttributeVariations() throws Exception {
    // Test various attribute scenarios
    String attrXml =
        "<?xml version=\"1.0\"?>"
            + "<product id=\"123\" name=\"Test Product\" price=\"99.99\" available=\"true\"/>";
    Element attrElement = parseXmlElement(attrXml);
    Object result = ObjectIntrospection.convert(attrElement, ctx);

    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> attrMap = (Map<String, Object>) result;
    assertEquals("123", attrMap.get("id"));
    assertEquals("Test Product", attrMap.get("name"));
    assertEquals("99.99", attrMap.get("price"));
    assertEquals("true", attrMap.get("available"));
  }

  @Test
  void testXmlNamespaceHandling() throws Exception {
    // Test XML with namespaces
    String nsXml =
        "<?xml version=\"1.0\"?>"
            + "<root xmlns:ns=\"http://example.com/ns\">"
            + "  <ns:element>namespaced content</ns:element>"
            + "  <regular>regular content</regular>"
            + "</root>";
    Element nsElement = parseXmlElement(nsXml);
    Object result = ObjectIntrospection.convert(nsElement, ctx);

    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> nsMap = (Map<String, Object>) result;
    // Namespaced elements should be handled (exact behavior depends on DOM parser)
    assertTrue(nsMap.size() > 0);
  }

  @Test
  void testXmlComplexRealWorldStructure() throws Exception {
    // Test realistic API response structure
    String apiXml =
        "<?xml version=\"1.0\"?>"
            + "<api-response>"
            + "  <metadata version=\"1.0\" timestamp=\"2024-01-01T00:00:00Z\"/>"
            + "  <data>"
            + "    <users>"
            + "      <user id=\"1\" active=\"true\">"
            + "        <name>Alice</name>"
            + "        <email>alice@example.com</email>"
            + "        <roles>"
            + "          <role>admin</role>"
            + "          <role>user</role>"
            + "        </roles>"
            + "      </user>"
            + "      <user id=\"2\" active=\"false\">"
            + "        <name>Bob</name>"
            + "        <email>bob@example.com</email>"
            + "        <roles>"
            + "          <role>user</role>"
            + "        </roles>"
            + "      </user>"
            + "    </users>"
            + "    <pagination>"
            + "      <page>1</page>"
            + "      <size>10</size>"
            + "      <total>2</total>"
            + "    </pagination>"
            + "  </data>"
            + "  <status>success</status>"
            + "</api-response>";

    Element apiElement = parseXmlElement(apiXml);
    Object result = ObjectIntrospection.convert(apiElement, ctx);

    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> apiMap = (Map<String, Object>) result;

    // Verify structure is preserved
    assertTrue(apiMap.containsKey("metadata"));
    assertTrue(apiMap.containsKey("data"));
    assertTrue(apiMap.containsKey("status"));

    @SuppressWarnings("unchecked")
    Map<String, Object> dataMap = (Map<String, Object>) apiMap.get("data");
    assertTrue(dataMap.containsKey("users"));
    assertTrue(dataMap.containsKey("pagination"));
  }

  @Test
  void testXmlTruncationListener() throws Exception {
    // Create a simple truncation listener to test the callback
    boolean[] truncationCalled = {false};
    ObjectIntrospection.TruncationListener listener = () -> truncationCalled[0] = true;

    StringBuilder longTextBuilder = new StringBuilder();
    for (int i = 0; i < MAX_STRING_SIZE + 100; i++) {
      longTextBuilder.append("A");
    }
    String longText = longTextBuilder.toString();
    String longXml = "<?xml version=\"1.0\"?><long>" + longText + "</long>";
    Element longElement = parseXmlElement(longXml);

    ObjectIntrospection.convert(longElement, ctx, listener);

    // Verify the listener was called
    assertTrue(truncationCalled[0]);
    assertTrue(ctx.isWafTruncated());
  }

  private int countNesting(Map<String, Object> object, int levels) {
    if (object.isEmpty()) {
      return levels;
    }

    for (Object value : object.values()) {
      if (value instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> childMap = (Map<String, Object>) value;
        return countNesting(childMap, levels + 1);
      }
    }
    return levels;
  }
}
