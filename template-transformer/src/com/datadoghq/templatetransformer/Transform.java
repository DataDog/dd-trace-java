package com.datadoghq.templatetransformer;

import com.sun.org.apache.xalan.internal.xslt.Process;

/**
 * Will transform a .jfc file to the datadog properties file (.jfp) used by the agent.
 * The reason for this transform is to:
 * 
 * 1. Lets us avoid hving xml dependencies and xml parsing code in out agent code.
 * 2. Lets us keep the settings for the profiling in a map, and programmatically 
 *    change details. 
 * 
 * Needs to run on a JDK 8.
 * 
 * @author Marcus Hirt
 */
public final class Transform {
	/**
	 * Transform -IN template.jfc -XSL stylesheet.xsl -OUT template.jfp
	 */
	public static void main(String[] args) {
		Process._main(args);
	}
}
