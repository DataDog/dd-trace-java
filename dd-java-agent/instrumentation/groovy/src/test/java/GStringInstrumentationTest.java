import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.StringModule;
import org.junit.Assume;
import org.junit.Test;

public class GStringInstrumentationTest extends AbstractGroovyTest {

  @Override
  public String suiteName() {
    return "foo.bar.GStringSuite";
  }

  @Test
  public void test_that_GString_is_instrumented() {
    Assume.assumeTrue(testSuite != null);
    final StringModule module = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(module);
    final String expected = "Hello World!";
    final String result = invoke("format", "Hello", "World!");
    assertThat(result, equalTo(expected));
    verify(module, times(1))
        .onStringFormat(
            eq(new String[] {"", " ", ""}),
            eq(new Object[] {"Hello", "World!"}),
            toString(expected));
  }
}
