package com.datadog.profiling.controller;

/** Creates the {@link Controller} used by the {@link ProfilingSystem}. */
@FunctionalInterface
public interface ControllerFactory {
  /**
   * @param context the context to be populated by the created controller(s)
   * @return the controller
   * @throws UnsupportedEnvironmentException if no controller is supported in this environment
   */
  Controller create(ControllerContext context) throws UnsupportedEnvironmentException;
}
