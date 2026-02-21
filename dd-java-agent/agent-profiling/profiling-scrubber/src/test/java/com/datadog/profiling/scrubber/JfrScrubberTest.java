package com.datadog.profiling.scrubber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.PLAIN_TEXT;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;

class JfrScrubberTest {

  @TempDir Path tempDir;

  private Path inputFile;

  @BeforeEach
  void setUp() throws IOException {
    inputFile = tempDir.resolve("input.jfr");
    try (InputStream is = getClass().getResourceAsStream("/test-recording.jfr")) {
      if (is == null) {
        throw new IllegalStateException("test-recording.jfr not found in test resources");
      }
      Files.copy(is, inputFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  @Test
  void scrubInitialSystemPropertyValues() throws Exception {
    JfrScrubber scrubber = DefaultScrubDefinition.create(null);
    Path outputFile = tempDir.resolve("output.jfr");
    scrubber.scrubFile(inputFile, outputFile);

    assertTrue(Files.exists(outputFile));
    assertTrue(Files.size(outputFile) > 0, "Scrubbed file should not be empty");

    // Verify scrubbed values contain only 'x' characters
    IItemCollection events = JfrLoaderToolkit.loadEvents(outputFile.toFile());
    IItemCollection systemPropertyEvents =
        events.apply(ItemFilters.type("jdk.InitialSystemProperty"));
    assertTrue(systemPropertyEvents.hasItems(), "Expected jdk.InitialSystemProperty events");

    IAttribute<String> valueAttr = attr("value", "value", "value", PLAIN_TEXT);
    for (IItemIterable itemIterable : systemPropertyEvents) {
      IMemberAccessor<String, IItem> accessor = valueAttr.getAccessor(itemIterable.getType());
      for (IItem item : itemIterable) {
        String value = accessor.getMember(item);
        if (value != null && !value.isEmpty()) {
          assertTrue(
              value.chars().allMatch(c -> c == 'x'),
              "System property value should be scrubbed: " + value);
        }
      }
    }
  }

  @Test
  void scrubWithNoMatchingEvents() throws Exception {
    // Scrubber with all default events excluded — nothing matches
    JfrScrubber scrubber = new JfrScrubber(name -> null);
    Path outputFile = tempDir.resolve("output.jfr");
    scrubber.scrubFile(inputFile, outputFile);

    // Output should be identical to input when no events match
    assertEquals(Files.size(inputFile), Files.size(outputFile));
  }

  @Test
  void scrubWithExcludedEventType() throws Exception {
    // Exclude jdk.InitialSystemProperty from scrubbing
    JfrScrubber scrubber =
        DefaultScrubDefinition.create(Collections.singletonList("jdk.InitialSystemProperty"));
    Path outputFile = tempDir.resolve("output.jfr");
    scrubber.scrubFile(inputFile, outputFile);

    assertTrue(Files.exists(outputFile));
    assertTrue(Files.size(outputFile) > 0);
  }
}
