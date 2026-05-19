import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

final class JUnitReport {
  private static final String FINAL_STATUS_PROPERTY = "dd_tags[test.final_status]";
  private static final Pattern HASH_CODE = Pattern.compile("@[0-9a-f]{5,}");
  private static final Pattern LOCALHOST_PORT = Pattern.compile("localhost:[0-9]{2,5}");

  private final Document document;

  private JUnitReport(Document document) {
    this.document = document;
  }

  static JUnitReport parse(Path xmlFile) throws Exception {
    var dbf = DocumentBuilderFactory.newInstance();
    dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    dbf.setXIncludeAware(false);
    dbf.setExpandEntityReferences(false);
    return new JUnitReport(dbf.newDocumentBuilder().parse(xmlFile.toFile()));
  }

  boolean addFileAttribute(String sourceFile) {
    var changed = false;
    for (var testcase : testcases()) {
      if (testcase.hasAttribute("time")) {
        changed |= !sourceFile.equals(testcase.getAttribute("file"));
        testcase.setAttribute("file", sourceFile);
      }
    }
    return changed;
  }

  boolean normalizeStableTestNames() {
    var changed = false;
    for (var testcase : testcases()) {
      var attributes = testcase.getAttributes();
      for (var i = 0; i < attributes.getLength(); i++) {
        var attribute = attributes.item(i);
        var value = attribute.getNodeValue();
        var normalized =
            LOCALHOST_PORT
                .matcher(HASH_CODE.matcher(value).replaceAll("@HASHCODE"))
                .replaceAll("localhost:PORT");
        if (!value.equals(normalized)) {
          attribute.setNodeValue(normalized);
          changed = true;
        }
      }
    }
    return changed;
  }

  void tagSyntheticFailures() {
    Map<String, List<Element>> initializationErrorsByClassname = new LinkedHashMap<>();
    for (var testcase : testcases()) {
      var name = testcase.getAttribute("name");
      if ("initializationError".equals(name)) {
        initializationErrorsByClassname
            .computeIfAbsent(testcase.getAttribute("classname"), ignored -> new ArrayList<>())
            .add(testcase);
      } else if ("executionError".equals(name) || "test exception".equals(name)) {
        addFinalStatusProperty(testcase, "skip", MissingPropertiesPlacement.APPEND_TO_TESTCASE);
      }
    }

    for (var group : initializationErrorsByClassname.values()) {
      for (var i = 0; i < group.size() - 1; i++) {
        addFinalStatusProperty(
            group.get(i), "skip", MissingPropertiesPlacement.APPEND_TO_TESTCASE);
      }
    }
  }

  void tagFinalStatuses() {
    for (var testcase : testcases()) {
      if (hasFinalStatusProperty(testcase)) {
        continue;
      }
      addFinalStatusProperty(
          testcase, finalStatus(testcase), MissingPropertiesPlacement.FIRST_CHILD);
    }
  }

  void write(Path xmlFile) throws Exception {
    Files.createDirectories(xmlFile.getParent());
    var tmpFile = Files.createTempFile(xmlFile.getParent(), "collect-results-", ".xml");
    try (OutputStream output = Files.newOutputStream(tmpFile)) {
      var transformerFactory = TransformerFactory.newInstance();
      transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      var transformer = transformerFactory.newTransformer();
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      transformer.transform(new DOMSource(document), new StreamResult(output));
    } catch (Exception e) {
      Files.deleteIfExists(tmpFile);
      throw e;
    }
    Files.move(tmpFile, xmlFile, StandardCopyOption.REPLACE_EXISTING);
  }

  private List<Element> testcases() {
    var testcases = document.getElementsByTagName("testcase");
    var elements = new ArrayList<Element>(testcases.getLength());
    for (var i = 0; i < testcases.getLength(); i++) {
      elements.add((Element) testcases.item(i));
    }
    return elements;
  }

  private boolean addFinalStatusProperty(
      Element testcase, String status, MissingPropertiesPlacement missingPropertiesPlacement) {
    var properties = firstChildElement(testcase, "properties");
    if (properties != null) {
      if (propertiesHasFinalStatusProperty(properties)) {
        return false;
      }
    } else {
      properties = document.createElement("properties");
      if (missingPropertiesPlacement == MissingPropertiesPlacement.FIRST_CHILD) {
        testcase.insertBefore(properties, testcase.getFirstChild());
      } else {
        testcase.appendChild(properties);
      }
    }

    var property = document.createElement("property");
    property.setAttribute("name", FINAL_STATUS_PROPERTY);
    property.setAttribute("value", status);
    properties.appendChild(property);
    return true;
  }

  private static boolean hasFinalStatusProperty(Element testcase) {
    var properties = firstChildElement(testcase, "properties");
    return properties != null && propertiesHasFinalStatusProperty(properties);
  }

  private static boolean propertiesHasFinalStatusProperty(Element properties) {
    var children = properties.getChildNodes();
    for (var i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element element
          && "property".equals(element.getTagName())
          && FINAL_STATUS_PROPERTY.equals(element.getAttribute("name"))) {
        return true;
      }
    }
    return false;
  }

  private static String finalStatus(Element testcase) {
    if (hasChildElement(testcase, "failure") || hasChildElement(testcase, "error")) {
      return "fail";
    }
    if (hasChildElement(testcase, "skipped")) {
      return "skip";
    }
    return "pass";
  }

  private static Element firstChildElement(Element parent, String tagName) {
    var children = parent.getChildNodes();
    for (var i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element element && tagName.equals(element.getTagName())) {
        return element;
      }
    }
    return null;
  }

  private static boolean hasChildElement(Element parent, String tagName) {
    return firstChildElement(parent, tagName) != null;
  }

  private enum MissingPropertiesPlacement {
    APPEND_TO_TESTCASE,
    FIRST_CHILD
  }
}
