package utils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class TestHelper {
  public static String getFixtureContent(String fixture) throws IOException, URISyntaxException {
    return new String(Files.readAllBytes(Paths.get(TestHelper.class.getResource(fixture).toURI())));
  }

  public static List<String> getFixtureLines(String fixture)
      throws IOException, URISyntaxException {
    return Files.readAllLines(Paths.get(TestHelper.class.getResource(fixture).toURI()));
  }
}
