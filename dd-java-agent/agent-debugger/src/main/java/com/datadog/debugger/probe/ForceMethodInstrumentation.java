package com.datadog.debugger.probe;

/**
 * This marker interface indicates that, regardless of the {@link Where} field, this probe should
 * always be treated as a method probe for purposes of instrumentation and evaluation. In the cases
 * of {@link ExceptionProbe} and {@link CodeOriginProbe}, e.g., these probes need to be instrumented
 * as method probes even though specific lines are listed in their {@link Where} fields because the
 * information they are collecting is very much related to specific lines but the lifecycle of a
 * line probe is insufficient to gather that data.
 */
public interface ForceMethodInstrumentation {}
