import org.w3c.dom.Element;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Tags synthetic testcases with `dd_tags[test.final_status]=skip` so Test Optimization does not
/// treat them as real failures.
///
/// **`initializationError`** — Gradle generates these for setup methods. When retried and eventually
/// successful, multiple testcases appear; only the last one passes. All intermediate attempts are
/// tagged skip. Files with only one (or zero) `initializationError` entries are left unmodified.
///
/// **`executionError`** and **`test exception`** — Framework-level synthetic failures that never
/// represent a real test result and never fail CI. All occurrences are tagged skip unconditionally.
///
/// Before:
///
/// ```
/// <testcase name="executionError" />
/// ```
///
/// After:
///
/// ```
/// <testcase name="executionError">
///   <properties>
///     <property name="dd_tags[test.final_status]" value="skip" />
///   </properties>
/// </testcase>
/// ```
///
/// Usage (Java 25): `java TagInitializationErrors.java junit-report.xml`

class TagInitializationErrors {
  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println("Usage: java TagInitializationErrors.java <xml-file>");
      System.exit(1);
    }
    var xmlFile = new File(args[0]);
    if (!xmlFile.exists()) {
      System.err.println("File not found: " + xmlFile);
      System.exit(1);
    }
    var dbf = DocumentBuilderFactory.newInstance();
    dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    dbf.setExpandEntityReferences(false);
    var doc = dbf.newDocumentBuilder().parse(xmlFile);
    var testcases = doc.getElementsByTagName("testcase");
    Map<String, List<Element>> byClassname = new LinkedHashMap<>();
    for (int i = 0; i < testcases.getLength(); i++) {
      var e = (Element) testcases.item(i);
      if ("initializationError".equals(e.getAttribute("name"))) {
        byClassname.computeIfAbsent(e.getAttribute("classname"), k -> new ArrayList<>()).add(e);
      }
    }
    boolean modified = false;
    for (var group : byClassname.values()) {
      if (group.size() <= 1) continue;
      for (int i = 0; i < group.size() - 1; i++) {
        if (tagSkip(doc, group.get(i))) modified = true;
      }
    }
    for (int i = 0; i < testcases.getLength(); i++) {
      var e = (Element) testcases.item(i);
      var name = e.getAttribute("name");
      if ("executionError".equals(name) || "test exception".equals(name)) {
        if (tagSkip(doc, e)) modified = true;
      }
    }
    if (!modified) return;
    var tmpFile = File.createTempFile("TagInitializationErrors", ".xml", xmlFile.getParentFile());
    try {
      var transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      transformer.transform(new DOMSource(doc), new StreamResult(tmpFile));
      Files.move(tmpFile.toPath(), xmlFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      tmpFile.delete();
      throw e;
    }
  }

  static boolean tagSkip(org.w3c.dom.Document doc, Element testcase) {
    var existingProperties = testcase.getElementsByTagName("properties");
    if (existingProperties.getLength() > 0) {
      var props = (Element) existingProperties.item(0);
      var existingProps = props.getElementsByTagName("property");
      for (int j = 0; j < existingProps.getLength(); j++) {
        if ("dd_tags[test.final_status]".equals(((Element) existingProps.item(j)).getAttribute("name"))) {
          return false;
        }
      }
      var property = doc.createElement("property");
      property.setAttribute("name", "dd_tags[test.final_status]");
      property.setAttribute("value", "skip");
      props.appendChild(property);
    } else {
      var properties = doc.createElement("properties");
      var property = doc.createElement("property");
      property.setAttribute("name", "dd_tags[test.final_status]");
      property.setAttribute("value", "skip");
      properties.appendChild(property);
      testcase.appendChild(properties);
    }
    return true;
  }
}
