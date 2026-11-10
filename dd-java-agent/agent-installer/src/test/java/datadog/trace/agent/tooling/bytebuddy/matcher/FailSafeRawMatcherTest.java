package datadog.trace.agent.tooling.bytebuddy.matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.test.util.DDJavaSpecification;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class FailSafeRawMatcherTest extends DDJavaSpecification {

  @SuppressWarnings("unchecked")
  private final ElementMatcher<TypeDescription> mockTypeMatcher = mock(ElementMatcher.class);

  @SuppressWarnings("unchecked")
  private final ElementMatcher<ClassLoader> mockLoaderMatcher = mock(ElementMatcher.class);

  private final FailSafeRawMatcher matcher =
      new FailSafeRawMatcher(mockTypeMatcher, mockLoaderMatcher, "test");

  @TableTest({
    "scenario                | loaderMatch | typeMatch | match",
    "loader and type match   | true        | true      | true ",
    "only loader matches     | true        | false     | false",
    "only type matches       | false       | true      | false",
    "neither loader nor type | false       | false     | false"
  })
  void testMatcher(boolean loaderMatch, boolean typeMatch, boolean match) {
    when(mockLoaderMatcher.matches(any())).thenReturn(loaderMatch);
    when(mockTypeMatcher.matches(any())).thenReturn(typeMatch);

    // when
    boolean result = matchObjectType();

    // then
    verify(mockLoaderMatcher).matches(any());
    if (loaderMatch) {
      verify(mockTypeMatcher).matches(any());
    } else {
      verifyNoMoreInteractions(mockTypeMatcher, mockLoaderMatcher);
    }
    assertEquals(match, result);
  }

  @Test
  void testLoaderMatcherException() {
    when(mockLoaderMatcher.matches(any()))
        .thenAnswer(invocation -> {
          throw new Exception("matcher exception");
        });

    // when
    boolean result = matchObjectType();

    // then
    verify(mockLoaderMatcher).matches(any());
    verifyNoMoreInteractions(mockTypeMatcher, mockLoaderMatcher);
    assertFalse(result); // default to false
  }

  @Test
  void testTypeMatcherException() {
    when(mockLoaderMatcher.matches(any())).thenReturn(true);
    when(mockTypeMatcher.matches(any()))
        .thenAnswer(invocation -> {
          throw new Exception("matcher exception");
        });

    // when
    boolean result = matchObjectType();

    // then
    verify(mockLoaderMatcher).matches(any());
    verify(mockTypeMatcher).matches(any());
    verifyNoMoreInteractions(mockTypeMatcher, mockLoaderMatcher);
    assertFalse(result); // default to false
  }

  private boolean matchObjectType() {
    return matcher.matches(TypeDescription.ForLoadedType.of(Object.class), null, null, null, null);
  }
}
