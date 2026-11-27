import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

class CountTests {
  private static final XMLInputFactory FACTORY = newFactory();

  public static void main(String[] args) throws Exception {
    var total = TestCounts.ZERO;
    long files = 0;
    try (var paths = Files.walk(Path.of(args[0]))) {
      var reports =
          paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".xml")).iterator();
      while (reports.hasNext()) {
        var path = reports.next();
        files++;
        if (args[1].equals("1")) {
          System.err.println("[count_tests] Processing file " + files + ": " + path);
        }
        total = total.plus(count(path));
      }
    }
    if (args[1].equals("1")) {
      System.err.println("[count_tests] Processed " + files + " XML files");
    }
    System.out.println(total.asLine());
  }

  static TestCounts count(Path path) throws IOException, XMLStreamException {
    long tests = 0;
    long failures = 0;
    long errors = 0;
    long skipped = 0;

    try (var input = Files.newInputStream(path)) {
      var reader = FACTORY.createXMLStreamReader(input);
      try {
        while (reader.hasNext()) {
          if (reader.next() == XMLStreamConstants.START_ELEMENT) {
            switch (reader.getLocalName()) {
              case "testcase" -> tests++;
              case "failure" -> failures++;
              case "error" -> errors++;
              case "skipped" -> skipped++;
              default -> {}
            }
          }
        }
      } finally {
        reader.close();
      }
    }
    return new TestCounts(tests, failures, errors, skipped);
  }

  private static XMLInputFactory newFactory() {
    var factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    return factory;
  }
}
