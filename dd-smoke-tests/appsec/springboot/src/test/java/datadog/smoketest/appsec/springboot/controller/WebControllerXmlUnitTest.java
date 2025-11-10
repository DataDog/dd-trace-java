package datadog.smoketest.appsec.springboot.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class WebControllerXmlUnitTest {

  @Test
  public void testXmlParsingBasic() throws Exception {
    // Test basic XML parsing functionality
    String xmlInput = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root><name>test</name></root>";

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document document = builder.parse(new ByteArrayInputStream(xmlInput.getBytes("UTF-8")));

    assertNotNull(document);
    Element rootElement = document.getDocumentElement();
    assertEquals("root", rootElement.getTagName());

    Element nameElement = (Element) rootElement.getElementsByTagName("name").item(0);
    assertEquals("test", nameElement.getTextContent());
  }

  @Test
  public void testXmlEndpointLogic() throws Exception {
    // Test the XML endpoint logic in isolation
    WebController controller = new WebController();

    String xmlBodyString =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><test><id>123</id><name>Sample</name></test>";

    try {
      // Create DOM Document from XML string
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document xmlDocument =
          builder.parse(new ByteArrayInputStream(xmlBodyString.getBytes("UTF-8")));

      ResponseEntity<Document> response = controller.apiSecurityXml(xmlDocument);

      assertEquals(200, response.getStatusCodeValue());
      assertNotNull(response.getBody());

      // Verify the returned XML Document contains expected elements
      Document responseDoc = response.getBody();
      String responseXml = documentToString(responseDoc);
      assertTrue(responseXml.contains("<response"));
      assertTrue(responseXml.contains("<status>success</status>"));
      assertTrue(responseXml.contains("<message>XML processed successfully</message>"));
      assertTrue(responseXml.contains("root_tag=\"test\""));
    } catch (Exception e) {
      fail("XML endpoint should not throw exception: " + e.getMessage());
    }
  }

  @Test
  public void testXmlWithAttributes() throws Exception {
    // Test XML with attributes
    String xmlInput =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><product id=\"123\" category=\"electronics\"><name>Laptop</name></product>";

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document document = builder.parse(new ByteArrayInputStream(xmlInput.getBytes("UTF-8")));

    assertNotNull(document);
    Element rootElement = document.getDocumentElement();
    assertEquals("product", rootElement.getTagName());
    assertEquals("123", rootElement.getAttribute("id"));
    assertEquals("electronics", rootElement.getAttribute("category"));
  }

  @Test
  public void testXmlWithNestedElements() throws Exception {
    // Test complex nested XML structure
    String xmlInput =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<api-request>"
            + "<metadata version=\"1.0\"/>"
            + "<data>"
            + "<users>"
            + "<user id=\"1\">"
            + "<name>Alice</name>"
            + "<email>alice@example.com</email>"
            + "</user>"
            + "</users>"
            + "</data>"
            + "</api-request>";

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document document = builder.parse(new ByteArrayInputStream(xmlInput.getBytes("UTF-8")));

    assertNotNull(document);
    Element rootElement = document.getDocumentElement();
    assertEquals("api-request", rootElement.getTagName());

    Element dataElement = (Element) rootElement.getElementsByTagName("data").item(0);
    assertNotNull(dataElement);

    Element usersElement = (Element) dataElement.getElementsByTagName("users").item(0);
    assertNotNull(usersElement);
  }

  @Test
  public void testInvalidXml() {
    // Test handling of invalid XML
    String invalidXml = "<invalid><unclosed>";

    WebController controller = new WebController();

    assertThrows(
        Exception.class,
        () -> {
          DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
          DocumentBuilder builder = factory.newDocumentBuilder();
          Document invalidDoc =
              builder.parse(new ByteArrayInputStream(invalidXml.getBytes("UTF-8")));
          controller.apiSecurityXml(invalidDoc);
        });
  }

  @Test
  public void testEmptyXml() throws Exception {
    // Test handling of minimal valid XML
    String emptyXmlString = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><empty/>";

    WebController controller = new WebController();

    // Create DOM Document from XML string
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document emptyXmlDoc =
        builder.parse(new ByteArrayInputStream(emptyXmlString.getBytes("UTF-8")));

    ResponseEntity<Document> response = controller.apiSecurityXml(emptyXmlDoc);

    assertEquals(200, response.getStatusCodeValue());
    assertNotNull(response.getBody());

    // Verify the returned XML Document contains expected elements
    Document responseDoc = response.getBody();
    String responseXml = documentToString(responseDoc);
    assertTrue(responseXml.contains("<response"));
    assertTrue(responseXml.contains("<status>success</status>"));
    assertTrue(responseXml.contains("root_tag=\"empty\""));
  }

  // Helper method to convert Document to String for verification
  private String documentToString(Document doc) {
    try {
      TransformerFactory tf = TransformerFactory.newInstance();
      Transformer transformer = tf.newTransformer();
      StringWriter writer = new StringWriter();
      transformer.transform(new DOMSource(doc), new StreamResult(writer));
      return writer.getBuffer().toString();
    } catch (Exception e) {
      throw new RuntimeException("Failed to convert Document to String", e);
    }
  }
}
