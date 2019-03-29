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
package com.datadoghq.profiling.controller.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * Toolkit for working with .jfp files.
 * 
 * @author Marcus Hirt
 */
public final class JfpUtils {
	private JfpUtils() {
		throw new UnsupportedOperationException("Toolkit!");
	}

	public static Map<String, String> readJfpFile(InputStream stream) throws IOException {
		if (stream == null) {
			throw new IllegalArgumentException("Cannot read jfp file from empty stream!");
		}
		Properties props = new Properties();
		try {
			props.load(stream);
		} finally {
			stream.close();
		}
		Map<String, String> map = new HashMap<String, String>();
		for (Entry<Object, Object> o : props.entrySet()) {
			map.put(String.valueOf(o.getKey()), String.valueOf(o.getValue()));
		}
		return map;
	}
	
	public static InputStream getNamedResource(String name) {
		return JfpUtils.class.getClassLoader().getResourceAsStream(name);
	}

	public static Map<String, String> readNamedJfpResource(String name) throws IOException {
		return readJfpFile(getNamedResource(name));
	}
}
