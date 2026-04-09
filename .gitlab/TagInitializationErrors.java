import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tags intermediate {@code initializationError} retries with {@code dd_tags[test.final_status]=skip}.
 *
 * <p>Gradle generates synthetic "initializationError" testcases in JUnit reports for setup methods.
 * When a setup is retried and eventually succeeds, multiple testcases are created, with only the
 * last one passing. All intermediate attempts are marked skip so Test Optimization is not misled.
 *
 * <p>For any suite with multiple {@code initializationError} test cases (when retries occurred), all entries
 * but the last one are tagged by this script with `dd_tags[test.final_status]=skip`. The last
 * entry is left unmodified, allowing <em>Test Optimization</em> to apply its default status inference based
 * on the actual outcome. Files with only one (or zero) {@code initializationError} test cases are left unmodified.
 *
 * <p>Usage (Java 25): {@code java TagInitializationErrors.java junit-report.xml}
 */
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
    var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile);
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
        var testcase = group.get(i);
        var existingProperties = testcase.getElementsByTagName("properties");
        if (existingProperties.getLength() > 0) {
          var props = (Element) existingProperties.item(0);
          var existingProps = props.getElementsByTagName("property");
          boolean alreadyTagged = false;
          for (int j = 0; j < existingProps.getLength(); j++) {
            if ("dd_tags[test.final_status]".equals(((Element) existingProps.item(j)).getAttribute("name"))) {
              alreadyTagged = true;
              break;
            }
          }
          if (alreadyTagged) continue;
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
        modified = true;
      }
    }
    if (!modified) return;
    var transformer = TransformerFactory.newInstance().newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.transform(new DOMSource(doc), new StreamResult(xmlFile));
  }
}
