package datadog.trace.instrumentation.akkahttp.appsec;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class UnmarshallerHelpersXmlTest {

  @Test
  public void testIsXmlContent() throws Exception {
    // Use reflection to access private method
    Method isXmlContentMethod =
        UnmarshallerHelpers.class.getDeclaredMethod("isXmlContent", String.class);
    isXmlContentMethod.setAccessible(true);

    // Test XML declaration
    assertTrue((Boolean) isXmlContentMethod.invoke(null, "<?xml version=\"1.0\"?><root></root>"));

    // Test simple XML element
    assertTrue((Boolean) isXmlContentMethod.invoke(null, "<users><user>test</user></users>"));

    // Test with whitespace
    assertTrue((Boolean) isXmlContentMethod.invoke(null, "  <root>content</root>  "));

    // Test non-XML content
    assertFalse((Boolean) isXmlContentMethod.invoke(null, "{\"json\": \"content\"}"));
    assertFalse((Boolean) isXmlContentMethod.invoke(null, "plain text"));
    assertFalse((Boolean) isXmlContentMethod.invoke(null, ""));
    assertFalse((Boolean) isXmlContentMethod.invoke(null, (String) null));
  }

  @Test
  public void testHandleXmlString() throws Exception {
    // Use reflection to access private method
    Method handleXmlStringMethod =
        UnmarshallerHelpers.class.getDeclaredMethod("handleXmlString", String.class);
    handleXmlStringMethod.setAccessible(true);

    String xmlContent =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<users>"
            + "  <user id=\"1\" active=\"true\">"
            + "    <name>Alice</name>"
            + "    <email>alice@example.com</email>"
            + "  </user>"
            + "</users>";

    Object result = handleXmlStringMethod.invoke(null, xmlContent);

    // Should return a list containing the converted XML structure
    assertNotNull(result);
    assertTrue(result instanceof List);

    @SuppressWarnings("unchecked")
    List<Object> resultList = (List<Object>) result;
    assertEquals(1, resultList.size());

    // The first element should be a Map representing the root element
    Object rootElement = resultList.get(0);
    assertTrue(rootElement instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> rootMap = (Map<String, Object>) rootElement;

    // Should have children
    assertTrue(rootMap.containsKey("children"));

    @SuppressWarnings("unchecked")
    List<Object> children = (List<Object>) rootMap.get("children");
    assertNotNull(children);
    assertTrue(children.size() > 0);
  }

  @Test
  public void testHandleXmlStringWithAttributes() throws Exception {
    Method handleXmlStringMethod =
        UnmarshallerHelpers.class.getDeclaredMethod("handleXmlString", String.class);
    handleXmlStringMethod.setAccessible(true);

    String xmlContent = "<user id=\"123\" active=\"true\">John</user>";

    Object result = handleXmlStringMethod.invoke(null, xmlContent);

    assertNotNull(result);
    assertTrue(result instanceof List);

    @SuppressWarnings("unchecked")
    List<Object> resultList = (List<Object>) result;
    assertEquals(1, resultList.size());

    @SuppressWarnings("unchecked")
    Map<String, Object> rootMap = (Map<String, Object>) resultList.get(0);

    // Should have attributes
    assertTrue(rootMap.containsKey("attributes"));

    @SuppressWarnings("unchecked")
    Map<String, String> attributes = (Map<String, String>) rootMap.get("attributes");
    assertEquals("123", attributes.get("id"));
    assertEquals("true", attributes.get("active"));

    // Should have children (text content)
    assertTrue(rootMap.containsKey("children"));

    @SuppressWarnings("unchecked")
    List<Object> children = (List<Object>) rootMap.get("children");
    assertTrue(children.contains("John"));
  }

  @Test
  public void testHandleXmlStringEmpty() throws Exception {
    Method handleXmlStringMethod =
        UnmarshallerHelpers.class.getDeclaredMethod("handleXmlString", String.class);
    handleXmlStringMethod.setAccessible(true);

    // Test empty/null content
    assertNull(handleXmlStringMethod.invoke(null, ""));
    assertNull(handleXmlStringMethod.invoke(null, "   "));
    assertNull(handleXmlStringMethod.invoke(null, (String) null));
  }

  @Test
  public void testHandleXmlStringInvalidXml() throws Exception {
    Method handleXmlStringMethod =
        UnmarshallerHelpers.class.getDeclaredMethod("handleXmlString", String.class);
    handleXmlStringMethod.setAccessible(true);

    String invalidXml = "<user><name>John</invalid>";

    try {
      handleXmlStringMethod.invoke(null, invalidXml);
      fail("Should have thrown an exception for invalid XML");
    } catch (Exception e) {
      // Expected - should throw an exception for malformed XML
      assertTrue(e.getCause() != null);
    }
  }
}
