package com.datadog.profiling.controller;

/**
 * Exception thrown when the profiling system is badly configured.
 */
public class BadConfigurationException extends Exception {
	private static final long serialVersionUID = 1L;

	public BadConfigurationException(String message) {
		super(message);
	}
}
