import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Tags intermediate initializationError retries with dd_tags[test.final_status]=skip.
 *
 * <p>Gradle generates synthetic "initializationError" testcases in JUnit reports for setup methods.
 * When a setup is retried and eventually succeeds, multiple testcases are created, with only the
 * last one passing. All intermediate attempts are marked skip so Test Optimization is not misled.
 *
 * <p>Usage (JEP 330): java TagInitializationErrors.java <xml-file>
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
    List<Element> initErrorCases = new ArrayList<>();
    for (int i = 0; i < testcases.getLength(); i++) {
      var e = (Element) testcases.item(i);
      if ("initializationError".equals(e.getAttribute("name"))) {
        initErrorCases.add(e);
      }
    }
    if (initErrorCases.size() <= 1) return;
    for (int i = 0; i < initErrorCases.size() - 1; i++) {
      var testcase = initErrorCases.get(i);
      var properties = doc.createElement("properties");
      var property = doc.createElement("property");
      property.setAttribute("name", "dd_tags[test.final_status]");
      property.setAttribute("value", "skip");
      properties.appendChild(property);
      testcase.appendChild(properties);
    }
    var transformer = TransformerFactory.newInstance().newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.transform(new DOMSource(doc), new StreamResult(xmlFile));
  }
}
