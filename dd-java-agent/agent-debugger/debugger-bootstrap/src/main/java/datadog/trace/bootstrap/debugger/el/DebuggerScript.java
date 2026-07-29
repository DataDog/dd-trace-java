package datadog.trace.bootstrap.debugger.el;

import datadog.trace.bootstrap.debugger.util.TimeoutChecker;

/**
 * A debugger EL script interface used for communication between the instrumented code and the
 * debugger EL.<br>
 * Because it must be reachable from the instrumented code it must be placed in bootstrap.
 */
public interface DebuggerScript<R> {
  R execute(ValueReferenceResolver valueRefResolver, TimeoutChecker timeoutChecker);
}
