package com.datadoghq.templatetransformer;

import com.sun.org.apache.xalan.internal.xslt.Process;

/**
 * Will transform a .jfc file to the datadog properties file used by the agent.
 * Needs to run on a JDK 8.
 * 
 * @author Marcus Hirt
 */
public final class Transform {
	/**
	 * Transform -IN template.jfc -XSL stylesheet.xsl -OUT settings.properties
	 *
	 * @param args
	 */
	public static void main(String[] args) {
		Process._main(args);
	}
}
