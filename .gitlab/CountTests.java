import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;

class CountTests {
  public static void main(String[] args) throws Exception {
    var factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    long tests = 0;
    long failures = 0;
    long errors = 0;
    long skipped = 0;
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
        try (var input = Files.newInputStream(path)) {
          var reader = factory.createXMLStreamReader(input);
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
      }
    }
    if (args[1].equals("1")) {
      System.err.println("[count_tests] Processed " + files + " XML files");
    }
    System.out.println(tests + " " + failures + " " + errors + " " + skipped);
  }
}
