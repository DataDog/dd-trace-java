package datadog.trace.common.writer;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test for {@link OtlpWriter} over a real HTTP transport.
 *
 * <p>Mirrors {@code DDAgentWriterCombinedTest} / {@code DDIntakeWriterCombinedTest} — see {@code
 * dd-trace-core/src/test/groovy/datadog/trace/common/writer/DDAgentWriterCombinedTest.groovy} for
 * the established pattern.
 */
class OtlpWriterCombinedTest {

  @Test
  @Disabled("TODO: implement — see class Javadoc for scope")
  void endToEndOverHttp() {}
}
