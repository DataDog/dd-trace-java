package datadog.trace.bootstrap;

import static datadog.trace.bootstrap.AgentBootstrap.exceptionCauseChainContains;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class AgentBootstrapTest {
  private static final String IO_EXCEPTION = "java.io.IOException";

  @Test
  void returnTrueWhenFirstExceptionInTheCauseChainIsTheSpecifiedException() {
    Exception ex = new IOException();

    assertTrue(exceptionCauseChainContains(ex, IO_EXCEPTION));
  }

  @Test
  void returnFalseWhenExceptionCauseChainDoesNotContainSpecifiedException() {
    Exception ex = new NullPointerException();

    assertFalse(exceptionCauseChainContains(ex, IO_EXCEPTION));
  }

  @Test
  void returnTrueWhenExceptionCauseChainContainsSpecifiedExceptionAsACause() {
    Exception ex = new Exception(new IOException());

    assertTrue(exceptionCauseChainContains(ex, IO_EXCEPTION));
  }

  @Test
  void returnFalseWhenExceptionCauseChainHasACycle() {
    Exception ex = mock(Exception.class);
    when(ex.getCause()).thenReturn(ex);

    assertFalse(exceptionCauseChainContains(ex, IO_EXCEPTION));
  }
}
