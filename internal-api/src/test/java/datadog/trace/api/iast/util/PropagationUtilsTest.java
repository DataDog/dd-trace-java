package datadog.trace.api.iast.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.CodecModule;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.propagation.StringModule;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class PropagationUtilsTest {

  private static final String VALUE = "http://test.com";

  private static final Answer<Void> THROW_EXCEPTION =
      invocation -> {
        throw new Exception("test exception");
      };

  @AfterEach
  void tearDown() {
    InstrumentationBridge.CODEC = null;
    InstrumentationBridge.STRING = null;
    InstrumentationBridge.PROPAGATION = null;
  }

  @Test
  void testOnUriCreateNull() {
    URI uri = URI.create(VALUE);

    URI result = PropagationUtils.onUriCreate(VALUE, uri);

    assertEquals(uri, result);
  }

  @Test
  void testOnStringBuilderToStringNull() {
    StringBuilder sb = new StringBuilder(VALUE);

    String result = PropagationUtils.onStringBuilderToString(sb, sb.toString());

    assertEquals(sb.toString(), result);
  }

  @Test
  void testOnStringBuilderAppendNull() {
    StringBuilder sb = new StringBuilder();

    StringBuilder result = PropagationUtils.onStringBuilderAppend(VALUE, sb.append(VALUE));

    assertSame(sb, result);
  }

  @Test
  void testOnUriCreate() {
    CodecModule iastModule = mock(CodecModule.class);
    InstrumentationBridge.registerIastModule(iastModule);
    URI uri = URI.create(VALUE);

    URI result = PropagationUtils.onUriCreate(VALUE, uri);

    assertEquals(uri, result);
    verify(iastModule).onUriCreate(uri, VALUE);
    verifyNoMoreInteractions(iastModule);
  }

  @Test
  void testOnStringBuilderToString() {
    StringModule iastModule = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(iastModule);
    StringBuilder sb = new StringBuilder(VALUE);

    String result = PropagationUtils.onStringBuilderToString(sb, sb.toString());

    assertEquals(sb.toString(), result);
    verify(iastModule).onStringBuilderToString(sb, VALUE);
    verifyNoMoreInteractions(iastModule);
  }

  @Test
  void testOnStringBuilderAppend() {
    StringModule iastModule = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(iastModule);
    StringBuilder sb = new StringBuilder();

    StringBuilder result = PropagationUtils.onStringBuilderAppend(VALUE, sb.append(VALUE));

    assertSame(sb, result);
    verify(iastModule).onStringBuilderAppend(sb, VALUE);
    verifyNoMoreInteractions(iastModule);
  }

  @Test
  void testTaintObjectIfTainted() {
    PropagationModule iastModule = mock(PropagationModule.class);
    InstrumentationBridge.registerIastModule(iastModule);
    String inputTest = "test";

    PropagationUtils.taintObjectIfTainted(VALUE, inputTest);

    verify(iastModule).taintObjectIfTainted(VALUE, inputTest);
    verifyNoMoreInteractions(iastModule);
  }

  @Test
  void testOnUriCreateThrowException() {
    CodecModule iastModule = mock(CodecModule.class);
    InstrumentationBridge.registerIastModule(iastModule);
    URI uri = URI.create(VALUE);
    doAnswer(THROW_EXCEPTION).when(iastModule).onUriCreate(uri, VALUE);

    URI result = PropagationUtils.onUriCreate(VALUE, uri);

    assertEquals(uri, result);
    verify(iastModule).onUriCreate(uri, VALUE);
    verify(iastModule).onUnexpectedException(eq("onUriCreate threw"), any(Exception.class));
    verifyNoMoreInteractions(iastModule);
  }

  @Test
  void testOnStringBuilderToStringThrowException() {
    StringModule iastModule = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(iastModule);
    StringBuilder sb = new StringBuilder(VALUE);
    doAnswer(THROW_EXCEPTION).when(iastModule).onStringBuilderToString(sb, VALUE);

    String result = PropagationUtils.onStringBuilderToString(sb, sb.toString());

    assertEquals(sb.toString(), result);
    verify(iastModule).onStringBuilderToString(sb, VALUE);
    verify(iastModule)
        .onUnexpectedException(eq("onStringBuilderToString threw"), any(Exception.class));
    verifyNoMoreInteractions(iastModule);
  }

  @Test
  void testOnStringBuilderAppendThrowException() {
    StringModule iastModule = mock(StringModule.class);
    InstrumentationBridge.registerIastModule(iastModule);
    StringBuilder sb = new StringBuilder();
    doAnswer(THROW_EXCEPTION).when(iastModule).onStringBuilderAppend(sb, VALUE);

    StringBuilder result = PropagationUtils.onStringBuilderAppend(VALUE, sb.append(VALUE));

    assertSame(sb, result);
    verify(iastModule).onStringBuilderAppend(sb, VALUE);
    verify(iastModule)
        .onUnexpectedException(eq("onStringBuilderAppend threw"), any(Exception.class));
    verifyNoMoreInteractions(iastModule);
  }

  @Test
  void testTaintObjectIfTaintedThrowException() {
    PropagationModule iastModule = mock(PropagationModule.class);
    InstrumentationBridge.registerIastModule(iastModule);
    String inputTest = "test";
    doAnswer(THROW_EXCEPTION).when(iastModule).taintObjectIfTainted(VALUE, inputTest);

    PropagationUtils.taintObjectIfTainted(VALUE, inputTest);

    verify(iastModule).taintObjectIfTainted(VALUE, inputTest);
    verify(iastModule)
        .onUnexpectedException(eq("taintObjectIfTainted threw"), any(Exception.class));
    verifyNoMoreInteractions(iastModule);
  }
}
