package datadog.trace.instrumentation.junit5;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

public class BeforeAfterOperationsTracer implements InvocationInterceptor {

  @Override
  public void interceptBeforeAllMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    traceInvocation(invocation, invocationContext.getExecutable(), "BeforeAll");
  }

  @Override
  public void interceptBeforeEachMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    traceInvocation(invocation, invocationContext.getExecutable(), "BeforeEach");
  }

  @Override
  public void interceptAfterEachMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    traceInvocation(invocation, invocationContext.getExecutable(), "AfterEach");
  }

  @Override
  public void interceptAfterAllMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    traceInvocation(invocation, invocationContext.getExecutable(), "AfterAll");
  }

  private static void traceInvocation(
      Invocation<Void> invocation, Method executable, String operationName) throws Throwable {
    AgentSpan agentSpan = AgentTracer.startSpan("junit", executable.getName());
    agentSpan.setTag(Tags.TEST_CALLBACK, operationName);
    try (AgentScope agentScope = AgentTracer.activateSpan(agentSpan)) {
      invocation.proceed();
    } catch (Throwable t) {
      agentSpan.addThrowable(t);
      throw t;
    } finally {
      agentSpan.finish();
    }
  }
}
