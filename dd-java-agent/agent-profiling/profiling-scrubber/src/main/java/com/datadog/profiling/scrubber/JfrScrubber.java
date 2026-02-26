package com.datadog.profiling.scrubber;

import io.jafar.tools.Scrubber;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Thin wrapper around {@link Scrubber} from jafar-tools, hiding jafar types from consumers outside
 * the profiling-scrubber module.
 */
public final class JfrScrubber {

  private final Function<String, Scrubber.ScrubField> scrubDefinition;

  /** Package-private: use {@link DefaultScrubDefinition#create} to obtain an instance. */
  JfrScrubber(Function<String, Scrubber.ScrubField> scrubDefinition) {
    this.scrubDefinition = scrubDefinition;
  }

  /**
   * Scrub the given file by replacing targeted field values with 'x' bytes.
   *
   * @param input the input file to scrub
   * @param output the output file to write the scrubbed content to
   * @throws Exception if an error occurs during parsing or writing
   */
  public void scrubFile(Path input, Path output) throws Exception {
    Scrubber.scrubFile(input, output, scrubDefinition);
  }
}
