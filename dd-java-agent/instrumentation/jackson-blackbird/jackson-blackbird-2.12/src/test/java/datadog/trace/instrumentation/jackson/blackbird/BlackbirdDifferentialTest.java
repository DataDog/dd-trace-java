package datadog.trace.instrumentation.jackson.blackbird;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BlackbirdDifferentialTest {

  private static ObjectMapper blackbird(Corpus.Setup setup) {
    final ObjectMapper m = new Differential.PlainObjectMapper();
    m.registerModule(new BlackbirdModule());
    setup.configure.accept(m);
    return m;
  }

  @ParameterizedTest(name = "{0} / {1}")
  @MethodSource("datadog.trace.instrumentation.jackson.blackbird.Differential#setupsAndCases")
  void sameBytesAndValues(Corpus.Setup setup, Corpus.Case c) throws Exception {
    Differential.assertSameOutput(Differential.plain(setup), blackbird(setup), c);
  }

  @ParameterizedTest(name = "{0} / {1}")
  @MethodSource("datadog.trace.instrumentation.jackson.blackbird.Differential#setupsAndInputs")
  void sameReadsAndErrors(Corpus.Setup setup, Corpus.Input in) {
    Differential.assertSameRead(Differential.plain(setup), blackbird(setup), in);
  }

  @Test
  void blackbirdIsActuallyInUse() throws Exception {
    Differential.assertBlackbirdAccessorsInUse(blackbird(Corpus.setups().get(0)));
  }
}
