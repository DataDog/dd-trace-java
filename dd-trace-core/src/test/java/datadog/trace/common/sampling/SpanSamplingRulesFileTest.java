package datadog.trace.common.sampling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

class SpanSamplingRulesFileTest extends SpanSamplingRulesTest {

  @TempDir Path tempDir;

  String createRulesFile(String rules) throws IOException {
    Path tempFile = tempDir.resolve("single-span-sampling-rules.json");
    Files.write(tempFile, rules.getBytes(StandardCharsets.UTF_8));
    return tempFile.toString();
  }

  @Override
  protected SpanSamplingRules deserializeRules(String jsonRules) {
    try {
      return SpanSamplingRules.deserializeFile(createRulesFile(jsonRules));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
