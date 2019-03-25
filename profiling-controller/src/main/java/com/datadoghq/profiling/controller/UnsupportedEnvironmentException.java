package com.datadoghq.profiling.controller;

/**
 * Exception thrown when the environment does not support a controller.
 * 
 * @author Marcus Hirt
 */
public class UnsupportedEnvironmentException extends Exception {
	private static final long serialVersionUID = 1L;
	
	public UnsupportedEnvironmentException(String message) {
		super(message);
	}
}
