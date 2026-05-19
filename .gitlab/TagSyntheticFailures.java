import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/// Tags synthetic testcases (`initializationError`, `executionError`, `test exception`) with
/// `dd_tags[test.final_status]=skip` so Test Optimization does not treat them as real failures.
/// The script is idempotent — testcases that already carry a `dd_tags[test.final_status]` property
/// are left unchanged.
///
/// **`initializationError`** — Gradle generates these for setup methods. When retried and
// eventually
/// successful, multiple testcases appear; only the last one passes. All intermediate attempts are
/// tagged skip. Groups with only one (or zero) `initializationError` entries per classname are left
// unmodified.
///
/// **`executionError`** and **`test exception`** — Framework-level synthetic failures that do not
/// represent real test results. Tagged skip unconditionally so Test Optimization treats them as
// non-failures.

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
/// Usage (Java 25): `java TagSyntheticFailures.java batch.list`
///
/// The argument is a list file with one XML path per line.
///  Each referenced XML is processed in turn.

class TagSyntheticFailures {
  public static void main(String[] args) throws Exception {
    var dbf = DocumentBuilderFactory.newInstance();
    dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    dbf.setExpandEntityReferences(false);
    var docBuilder = dbf.newDocumentBuilder();
    var transformerFactory = TransformerFactory.newInstance();

    var listFile = new File(args[0]);
    for (String line : Files.readAllLines(listFile.toPath())) {
      processFile(new File(line), docBuilder, transformerFactory);
    }
  }

  static void processFile(File xmlFile, DocumentBuilder docBuilder, TransformerFactory transformerFactory) throws Exception {
    if (!xmlFile.exists()) {
      System.err.println("xml file not found, skipping: " + xmlFile);
      return;
    }

    var doc = docBuilder.parse(xmlFile);
    var testCases = doc.getElementsByTagName("testcase");
    Map<String, List<Element>> byClassname = new LinkedHashMap<>();
    boolean modified = false;
    for (int i = 0; i < testCases.getLength(); i++) {
      var e = (Element) testCases.item(i);
      var name = e.getAttribute("name");
      if ("initializationError".equals(name)) {
        byClassname.computeIfAbsent(e.getAttribute("classname"), k -> new ArrayList<>()).add(e);
      } else if ("executionError".equals(name) || "test exception".equals(name)) {
        if (tagFinalStatus(doc, e, "skip")) modified = true;
      }
    }

    for (var group : byClassname.values()) {
      for (int i = 0; i < group.size() - 1; i++) {
        if (tagFinalStatus(doc, group.get(i), "skip")) {
          modified = true;
        }
      }
    }

    // Tag remaining testcases with their pass/skip/fail status.
    // tagFinalStatus is idempotent — already-tagged synthetics from the passes above are skipped.
    for (int i = 0; i < testCases.getLength(); i++) {
      var e = (Element) testCases.item(i);
      if (tagFinalStatus(doc, e, computeStatus(e))) modified = true;
    }

    if (!modified) {
      return;
    }

    var transformer = transformerFactory.newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.transform(new DOMSource(doc), new StreamResult(xmlFile));
  }

  static Element firstChildElement(Element parent, String tagName) {
    var children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      var child = children.item(i);
      if (child instanceof Element e && tagName.equals(e.getTagName())) {
        return e;
      }
    }
    return null;
  }

  static boolean tagFinalStatus(Document doc, Element testcase, String status) {
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
      props.appendChild(newStatusProperty(doc, status));
    } else {
      var properties = doc.createElement("properties");
      properties.appendChild(newStatusProperty(doc, status));
      testcase.appendChild(properties);
    }
    return true;
  }

  static Element newStatusProperty(Document doc, String status) {
    var p = doc.createElement("property");
    p.setAttribute("name", "dd_tags[test.final_status]");
    p.setAttribute("value", status);
    return p;
  }

  /// Derives a testcase status: failure/error -> fail, skipped -> skip, else pass.
  static String computeStatus(Element testcase) {
    var children = testcase.getChildNodes();
    boolean hasSkipped = false;
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element e) {
        var tag = e.getTagName();
        if ("failure".equals(tag) || "error".equals(tag)) return "fail";
        if ("skipped".equals(tag)) hasSkipped = true;
      }
    }
    return hasSkipped ? "skip" : "pass";
  }
}
