package datadog.trace.bootstrap.instrumentation;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.InputSource;

/**
 * Utility class for converting W3C DOM XML structures to Map/List representations that are
 * compatible with WAF analysis and schema extraction.
 *
 * <p>This centralized utility eliminates code duplication across multiple instrumentation modules
 * that need to process XML content for AppSec analysis.
 */
public final class XmlDomUtils {

  /** Default maximum recursion depth for XML DOM conversion to prevent stack overflow. */
  public static final int DEFAULT_MAX_CONVERSION_DEPTH = 15;

  private XmlDomUtils() {
    // Utility class - prevent instantiation
  }

  /**
   * Convert a W3C DOM Document to a WAF-compatible Map/List structure.
   *
   * @param document the XML document to convert
   * @param maxRecursion maximum recursion depth to prevent stack overflow
   * @return converted structure wrapped in a list for consistency, or null if document is null
   */
  public static Object convertDocument(Document document, int maxRecursion) {
    if (document == null) {
      return null;
    }

    return convertW3cNode(document.getDocumentElement(), maxRecursion);
  }

  /**
   * Convert a W3C DOM Element to a WAF-compatible Map/List structure.
   *
   * @param element the XML element to convert
   * @param maxRecursion maximum recursion depth to prevent stack overflow
   * @return converted structure wrapped in a list for consistency, or null if element is null
   */
  private static Object convertElement(Element element, int maxRecursion) {
    if (element == null) {
      return null;
    }

    return convertW3cNode(element, maxRecursion);
  }

  /**
   * Convert a W3C DOM Node to a WAF-compatible Map/List structure.
   *
   * <p>This method recursively processes XML nodes, converting: - Elements to Maps with
   * "attributes" and "children" keys - Text nodes to their trimmed string content - Other node
   * types are ignored (return null)
   *
   * @param node the XML node to convert
   * @param maxRecursion maximum recursion depth to prevent stack overflow
   * @return Map for elements, String for text nodes, null for other types or when maxRecursion <= 0
   */
  private static Object convertW3cNode(Node node, int maxRecursion) {
    if (node == null || maxRecursion <= 0) {
      return null;
    }

    if (node instanceof Element) {
      return convertElementNode((Element) node, maxRecursion);
    } else if (node instanceof Text) {
      return convertTextNode((Text) node);
    }

    // Ignore other node types (comments, processing instructions, etc.)
    return null;
  }

  /** Convert an Element node to a Map with attributes and children. */
  private static Map<String, Object> convertElementNode(Element element, int maxRecursion) {
    Map<String, String> attributes = Collections.emptyMap();
    if (element.hasAttributes()) {
      attributes = new HashMap<>();
      NamedNodeMap attrMap = element.getAttributes();
      for (int i = 0; i < attrMap.getLength(); i++) {
        Attr item = (Attr) attrMap.item(i);
        attributes.put(item.getName(), item.getValue());
      }
    }

    List<Object> children = Collections.emptyList();
    if (element.hasChildNodes()) {
      NodeList childNodes = element.getChildNodes();
      children = new ArrayList<>(childNodes.getLength());
      for (int i = 0; i < childNodes.getLength(); i++) {
        Node item = childNodes.item(i);
        Object childResult = convertW3cNode(item, maxRecursion - 1);
        if (childResult != null) {
          children.add(childResult);
        }
      }
    }

    Map<String, Object> repr = new HashMap<>();
    if (!attributes.isEmpty()) {
      repr.put("attributes", attributes);
    }
    if (!children.isEmpty()) {
      repr.put("children", children);
    }
    return repr;
  }

  /** Convert a Text node to its trimmed string content. */
  private static String convertTextNode(Text textNode) {
    String textContent = textNode.getTextContent();
    if (textContent != null) {
      textContent = textContent.trim();
      if (!textContent.isEmpty()) {
        return textContent;
      }
    }
    return null;
  }

  /**
   * Check if a string contains XML content by looking for XML declaration or root element.
   *
   * @param content the string content to check
   * @return true if the string contains XML content, false otherwise
   */
  public static boolean isXmlContent(String content) {
    if (content == null || content.trim().isEmpty()) {
      return false;
    }
    String trimmed = content.trim();

    // Explicitly exclude JSON content
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      return false;
    }

    return trimmed.startsWith("<?xml")
        || (trimmed.startsWith("<")
            && trimmed.endsWith(">")
            && (trimmed.contains("</") || trimmed.contains("/>")));
  }

  /**
   * Process XML content (strings or DOM objects) for WAF compatibility using the default recursion
   * depth. This ensures XML attack payloads are properly detected by the WAF.
   *
   * @param xmlObj the XML object to process (can be Document, Element, Node, or String)
   * @return processed XML structure compatible with WAF analysis, or null if processing fails
   */
  public static Object processXmlForWaf(Object xmlObj) {
    return processXmlForWaf(xmlObj, DEFAULT_MAX_CONVERSION_DEPTH);
  }

  /**
   * Process XML content (strings or DOM objects) for WAF compatibility. This ensures XML attack
   * payloads are properly detected by the WAF.
   *
   * @param xmlObj the XML object to process (can be Document, Element, Node, or String)
   * @param maxRecursion maximum recursion depth to prevent stack overflow
   * @return processed XML structure compatible with WAF analysis, or null if processing fails
   */
  public static Object processXmlForWaf(Object xmlObj, int maxRecursion) {
    if (xmlObj == null) {
      return null;
    }

    // Handle W3C DOM objects directly
    if (xmlObj instanceof Document) {
      return convertDocument((Document) xmlObj, maxRecursion);
    }

    if (xmlObj instanceof Element) {
      return convertElement((Element) xmlObj, maxRecursion);
    }

    if (xmlObj instanceof Node) {
      // Return the converted node directly
      return convertW3cNode((Node) xmlObj, maxRecursion);
    }

    // Handle XML strings by parsing them first
    if (xmlObj instanceof String) {
      try {
        return parseXmlStringToWafFormat((String) xmlObj, maxRecursion);
      } catch (Exception e) {
        // Return null if parsing fails - let caller handle logging
        return null;
      }
    }

    return null;
  }

  /**
   * Convert XML string to WAF-compatible format following Spring framework pattern. This ensures
   * XML attack payloads are properly detected by the WAF.
   *
   * @param xmlContent the XML string content to parse
   * @param maxRecursion maximum recursion depth to prevent stack overflow
   * @return parsed XML structure compatible with WAF analysis
   * @throws Exception if XML parsing fails
   */
  private static Object parseXmlStringToWafFormat(String xmlContent, int maxRecursion)
      throws Exception {
    if (xmlContent == null || xmlContent.trim().isEmpty()) {
      return null;
    }

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    // Security settings to prevent XXE attacks during parsing
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setExpandEntityReferences(false);

    DocumentBuilder builder = factory.newDocumentBuilder();
    Document document = builder.parse(new InputSource(new StringReader(xmlContent)));

    return convertDocument(document, maxRecursion);
  }

  /**
   * Convert XML string to WAF-compatible format using the default recursion depth. This is a
   * convenience method that wraps parseXmlStringToWafFormat and handles exceptions internally.
   *
   * @param xmlContent the XML string content to handle
   * @return parsed XML structure compatible with WAF analysis, or null if parsing fails
   */
  public static Object handleXmlString(String xmlContent) {
    return handleXmlString(xmlContent, DEFAULT_MAX_CONVERSION_DEPTH);
  }

  /**
   * Convert XML string to WAF-compatible format. This is a convenience method that wraps
   * parseXmlStringToWafFormat and handles exceptions internally.
   *
   * @param xmlContent the XML string content to handle
   * @param maxRecursion maximum recursion depth to prevent stack overflow
   * @return parsed XML structure compatible with WAF analysis, or null if parsing fails
   */
  public static Object handleXmlString(String xmlContent, int maxRecursion) {
    try {
      return parseXmlStringToWafFormat(xmlContent, maxRecursion);
    } catch (Exception e) {
      // Return null if parsing fails - let caller handle logging
      return null;
    }
  }
}
