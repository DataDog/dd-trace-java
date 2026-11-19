package com.datadog.openfeature;

import com.datadog.openfeature.internal.JsonReading;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reads the opt-in scale suites of the ffe-system-test-data submodule, for {@code ScaleSuiteTest}
 * and the scale benchmarks.
 */
final class ScaleSuites {
  private static final String SUITES_PATH =
      "dd-openfeature/src/test/fixtures/ffe-system-test-data/evaluation-suites";

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

    /**
     * A provider that evaluates against this suite's configuration. Its settings ignore the JVM
     * system properties and environment, so span enrichment stays off and the expected selection
     * metadata holds.
     */
    Provider provider() throws IOException {
      final DDEvaluator evaluator =
          new DDEvaluator(
              () -> {}, AgentBridge.NONE, TestSettings.of(), ExperimentationOptOutMode.DISABLED);
      evaluator.accept(UniversalFlagConfigParser.parse(config()));
      return new Provider(new Provider.Options(), evaluator, false);
    }

    /** The names of the case files, without the {@code .json} extension, in sorted order. */
    List<String> caseFiles() throws IOException {
      final List<String> names = new ArrayList<>();
      try (DirectoryStream<Path> files =
          Files.newDirectoryStream(directory.resolve("cases"), "*.json")) {
        for (final Path file : files) {
          final String name = file.getFileName().toString();
          names.add(name.substring(0, name.length() - ".json".length()));
        }
      }
      Collections.sort(names);
      return names;
    }

    /** The cases of {@code cases/<name>.json}. */
    List<ScaleCase> cases(final String name) throws IOException {
      final List<ScaleCase> cases = new ArrayList<>();
      for (final Object json :
          (List<?>)
              JsonReading.read(
                  Files.readAllBytes(directory.resolve("cases").resolve(name + ".json")))) {
        cases.add(new ScaleCase((Map<?, ?>) json));
      }
      if (cases.isEmpty()) {
        throw new IllegalStateException("The case file " + name + " has no cases");
      }
      return cases;
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
}
