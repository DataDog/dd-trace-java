/*
 * Copyright 2019 Datadog
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadog.profiling.templatetransformer;

import com.sun.org.apache.xalan.internal.xslt.Process;

/**
 * Will transform a .jfc file to the datadog properties file (.jfp) used by the agent. The reason
 * for this transform is to:
 * <ol>
 * <li>Lets us avoid hving xml dependencies and xml parsing code in out agent code.</li>
 * <li>Lets us keep the settings for the profiling in a map, and programmatically change details.
 * Needs to run on a JDK 8.</li>
 * </ol>
 */
public final class Transform {
	/**
	 * Transform -IN template.jfc -XSL stylesheet.xsl -OUT template.jfp
	 */
	public static void main(String[] args) {
		Process._main(args);
	}
}
