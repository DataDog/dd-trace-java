package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDTraceId;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor.Invocation;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

class ScopeDiagnosticsExtensionTest {

  private static class FailingFixture {
    @BeforeAll
    static void failSuiteSetup() {}
  }

  @AfterEach
  void tearDown() {
    ScopeDiagnostics.reset();
  }

  @Test
  @SuppressWarnings("unchecked")
  void reportsPendingSuiteSetupBeforeStartingCleanupWindow() throws Throwable {
    ScopeDiagnosticsExtension extension = new ScopeDiagnosticsExtension();
    ExtensionContext context = mock(ExtensionContext.class);
    when(context.getTestClass()).thenReturn(Optional.of(FailingFixture.class));
    doReturn(FailingFixture.class).when(context).getRequiredTestClass();
    extension.beforeAll(context);

    Invocation<Void> harnessSetup = mock(Invocation.class);
    ReflectiveInvocationContext<Method> harnessContext = mock(ReflectiveInvocationContext.class);
    when(harnessContext.getExecutable())
        .thenReturn(AbstractInstrumentationTest.class.getDeclaredMethod("initAll"));
    extension.interceptBeforeAllMethod(harnessSetup, harnessContext, context);

    ContextContinuation continuation = Context.root().capture();
    ScopeDiagnostics.recordCapture(
        ScopeDiagnostics.recordingWindow(),
        continuation,
        DDTraceId.from(1),
        2,
        "suite.setup",
        (byte) 0);

    RuntimeException setupFailure = new RuntimeException("suite setup failed");
    Invocation<Void> failingSetup = mock(Invocation.class);
    doThrow(setupFailure).when(failingSetup).proceed();
    ReflectiveInvocationContext<Method> failingContext = mock(ReflectiveInvocationContext.class);
    when(failingContext.getExecutable())
        .thenReturn(FailingFixture.class.getDeclaredMethod("failSuiteSetup"));

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> extension.interceptBeforeAllMethod(failingSetup, failingContext, context));

    assertSame(setupFailure, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertInstanceOf(AssertionError.class, thrown.getSuppressed()[0]);
    assertNotNull(ScopeDiagnostics.recordingWindow(), "suite cleanup must use a fresh window");
    assertTrue(ScopeDiagnostics.report().records().isEmpty());

    continuation.release();
  }
}
