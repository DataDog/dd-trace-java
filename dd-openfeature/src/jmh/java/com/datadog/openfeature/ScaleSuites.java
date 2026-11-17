package com.datadog.openfeature;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the opt-in scale suites of the ffe-system-test-data submodule. */
final class ScaleSuites {
  private static final String SUITES_PATH =
      "dd-smoke-tests/openfeature/src/test/resources/ffe-system-test-data/evaluation-suites";
  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  private ScaleSuites() {}

  /** One suite directory: its configuration and its case files. */
  static final class Suite {
    private final Path directory;

    private Suite(final Path directory) {
      this.directory = directory;
    }

    byte[] config() throws IOException {
      return Files.readAllBytes(directory.resolve("ufc-config.json"));
    }

    /** The cases of {@code cases/<name>.json}. */
    List<?> cases(final String name) throws IOException {
      return (List<?>) read(directory.resolve("cases").resolve(name + ".json"));
    }
  }

  static Suite load(final String name) {
    Path directory = Paths.get("").toAbsolutePath();
    while (directory != null) {
      final Path candidate = directory.resolve(SUITES_PATH).resolve(name);
      if (Files.exists(candidate.resolve("ufc-config.json"))) {
        return new Suite(candidate);
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException(
        "Unable to find the FFE suite " + name + ": initialize the ffe-system-test-data submodule");
  }

  /** Reads a JSON file: integral numbers as Long, other numbers as Double. */
  private static Object read(final Path file) throws IOException {
    try (JsonParser parser = JSON_FACTORY.createParser(Files.readAllBytes(file))) {
      parser.nextToken();
      return readValue(parser);
    }
  }

  private static Object readValue(final JsonParser parser) throws IOException {
    final JsonToken token = parser.currentToken();
    switch (token) {
      case START_OBJECT:
        final Map<String, Object> object = new LinkedHashMap<>();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
          final String name = parser.currentName();
          parser.nextToken();
          object.put(name, readValue(parser));
        }
        return object;
      case START_ARRAY:
        final List<Object> array = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          array.add(readValue(parser));
        }
        return array;
      case VALUE_STRING:
        return parser.getText();
      case VALUE_NUMBER_INT:
        return parser.getLongValue();
      case VALUE_NUMBER_FLOAT:
        return parser.getDoubleValue();
      case VALUE_TRUE:
        return Boolean.TRUE;
      case VALUE_FALSE:
        return Boolean.FALSE;
      case VALUE_NULL:
        return null;
      default:
        throw new IOException("Unexpected JSON token: " + token);
    }
  }
}
