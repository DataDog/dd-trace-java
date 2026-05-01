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

/// Tags synthetic testcases (`initializationError`, `executionError`, `test exception`) with
/// `dd_tags[test.final_status]=skip` so Test Optimization does not treat them as real failures.
/// The script is idempotent — testcases that already carry a `dd_tags[test.final_status]` property
/// are left unchanged.
///
/// **`initializationError`** — Gradle generates these for setup methods. When retried and eventually
/// successful, multiple testcases appear; only the last one passes. All intermediate attempts are
/// tagged skip. Groups with only one (or zero) `initializationError` entries per classname are left unmodified.
///
/// **`executionError`** and **`test exception`** — Framework-level synthetic failures that do not
/// represent real test results. Tagged skip unconditionally so Test Optimization treats them as non-failures.
///
/// Before (two retries of the same class — first is intermediate, second is the final outcome):
///
/// ```
/// <testcase name="initializationError" classname="com.example.MyTest" />
/// <testcase name="initializationError" classname="com.example.MyTest" />
/// ```
///
/// After (only the intermediate attempt is tagged; the last entry is left untouched):
///
/// ```
/// <testcase name="initializationError" classname="com.example.MyTest">
///   <properties>
///     <property name="dd_tags[test.final_status]" value="skip" />
///   </properties>
/// </testcase>
/// <testcase name="initializationError" classname="com.example.MyTest" />
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
    boolean modified = false;
    for (int i = 0; i < testcases.getLength(); i++) {
      var e = (Element) testcases.item(i);
      var name = e.getAttribute("name");
      if ("initializationError".equals(name)) {
        byClassname.computeIfAbsent(e.getAttribute("classname"), k -> new ArrayList<>()).add(e);
      } else if ("executionError".equals(name) || "test exception".equals(name)) {
        if (tagSkip(doc, e)) modified = true;
      }
    }
    for (var group : byClassname.values()) {
      if (group.size() <= 1) continue;
      for (int i = 0; i < group.size() - 1; i++) {
        if (tagSkip(doc, group.get(i))) modified = true;
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

  static Element firstChildElement(Element parent, String tagName) {
    var children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      var child = children.item(i);
      if (child instanceof Element e && tagName.equals(e.getTagName())) return e;
    }
    return null;
  }

  static boolean tagSkip(org.w3c.dom.Document doc, Element testcase) {
    var props = firstChildElement(testcase, "properties");
    if (props != null) {
      var children = props.getChildNodes();
      for (int j = 0; j < children.getLength(); j++) {
        if (children.item(j) instanceof Element e
            && "property".equals(e.getTagName())
            && "dd_tags[test.final_status]".equals(e.getAttribute("name"))) {
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
