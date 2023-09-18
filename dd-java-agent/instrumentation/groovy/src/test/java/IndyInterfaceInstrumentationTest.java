import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.StringModule;
import datadog.trace.api.iast.sink.WeakHashModule;
import java.util.Locale;
import org.junit.Assume;
import org.junit.Test;

public class IndyInterfaceInstrumentationTest extends AbstractGroovyTest {

  @Override
  public String suiteName() {
    return "foo.bar.IndyInterfaceSuite";
  }

  @Test
  public void test_that_new_string_GString_is_instrumented() {
    Assume.assumeTrue(testSuite != null);
    final StringModule module = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(module);
    final String expected = "Hello World!";
    final String result = invoke("init", "Hello World!");
    assertThat(result, equalTo(expected));
    verify(module, times(1)).onStringConstructor(toString(expected), toString(expected));
  }

  @Test
  public void test_that_concat_String_String_is_instrumented() {
    Assume.assumeTrue(testSuite != null);
    final StringModule module = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(module);
    final String expected = "Hello World!";
    final String result = invoke("concat", "Hello ", "World!");
    assertThat(result, equalTo(expected));
    verify(module, times(1)).onStringConcat("Hello ", "World!", result);
  }

  @Test
  public void test_that_concat_GString_GString_is_instrumented() {
    Assume.assumeTrue(testSuite != null);
    final StringModule module = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(module);
    final String expected = "Hello  World!";
    final String result = invoke("concat1", "Hello", "World!");
    assertThat(result, equalTo(expected));
    verify(module, times(1)).onStringConcat(toString("Hello "), toString(" World!"), eq(result));
  }

  @Test
  public void test_that_concat_GString_String_is_instrumented() {
    Assume.assumeTrue(testSuite != null);
    final StringModule module = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(module);
    final String expected = "Hello World!";
    final String result = invoke("concat2", "Hello", "World!");
    assertThat(result, equalTo(expected));
    verify(module, times(1)).onStringConcat(toString("Hello "), eq("World!"), eq(result));
  }

  @Test
  public void test_that_concat_String_GString_is_instrumented() {
    Assume.assumeTrue(testSuite != null);
    final StringModule module = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(module);
    final String expected = "Hello World!";
    final String result = invoke("concat3", "Hello", "World!");
    assertThat(result, equalTo(expected));
    verify(module, times(1)).onStringConcat(eq("Hello"), toString(" World!"), eq(result));
  }

  @Test
  public void test_that_subSequence_is_instrumented() {
    Assume.assumeTrue(testSuite != null);
    final StringModule module = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(module);
    final String expected = "Hello";
    final String result = invoke("subSequence", "Hello World!", 0, 5);
    assertThat(result, equalTo(expected));
    verify(module, times(1))
        .onStringSubSequence(toString("Hello World!"), eq(0), eq(5), eq(result));
  }

  @Test
  public void test_that_toUpperCase_is_instrumented() {
    Assume.assumeTrue(testSuite != null);
    final StringModule module = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(module);
    final String expected = "HELLO WORLD!";
    final String result = invoke("toUpperCase", "Hello World!");
    assertThat(result, equalTo(expected));
    verify(module, times(1)).onStringToUpperCase(toString("Hello World!"), eq(result));
  }

  @Test
  public void test_that_toLowerCase_is_instrumented() {
    Assume.assumeTrue(testSuite != null);
    final StringModule module = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(module);
    final String expected = "hello world!";
    final String result = invoke("toLowerCase", "Hello World!");
    assertThat(result, equalTo(expected));
    verify(module, times(1)).onStringToLowerCase(toString("Hello World!"), eq(result));
  }

  @Test
  public void test_that_join_is_instrumented() {
    Assume.assumeTrue(testSuite != null);
    final StringModule module = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(module);
    final String expected = "Hello,World!";
    final String result = invoke("join", ",", "Hello", "World!");
    assertThat(result, equalTo(expected));
    verify(module, times(1))
        .onStringJoin(eq(result), toString(","), eq(new CharSequence[] {"Hello", "World!"}));
  }

  @Test
  public void test_that_format_is_instrumented() {
    Assume.assumeTrue(testSuite != null);
    final StringModule module = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(module);
    final String expected = "Hello World!";

    final String result = invoke("format", "%s %s", "Hello", "World!");
    assertThat(result, equalTo(expected));
    verify(module, times(1))
        .onStringFormat(toString("%s %s"), eq(new Object[] {"Hello", "World!"}), eq(result));

    final String result2 = invoke("format", Locale.getDefault(), "%s %s", "Hello", "World!");
    assertThat(result2, equalTo(expected));
    verify(module, times(1))
        .onStringFormat(
            any(Locale.class),
            toString("%s %s"),
            eq(new Object[] {"Hello", "World!"}),
            eq(result2));
  }

  @Test
  public void test_that_hash_is_instrumented() {
    Assume.assumeTrue(testSuite != null);
    final WeakHashModule module = mock(WeakHashModule.class);
    InstrumentationBridge.registerIastModule(module);
    final String algorithm = "MD5";
    invoke("hash", algorithm, "Hello World!");
    verify(module, times(1)).onHashingAlgorithm(algorithm);
  }

  @Test
  public void test_that_failing_module_does_not_break_code() {
    Assume.assumeTrue(testSuite != null);
    final StringModule module = mock(StringModule.class);
    doThrow(new VeryBadException())
        .when(module)
        .onStringToLowerCase(any(CharSequence.class), any(CharSequence.class));
    InstrumentationBridge.registerIastModule(module);
    final String expected = "hello world!";
    final String result = invoke("toLowerCase", "Hello World!");
    assertThat(result, equalTo(expected));
    verify(module, times(1)).onUnexpectedException(any(String.class), any(VeryBadException.class));
  }

  public static class VeryBadException extends RuntimeException {
    VeryBadException() {
      super("Boom!!!!!!");
    }
  }
}
