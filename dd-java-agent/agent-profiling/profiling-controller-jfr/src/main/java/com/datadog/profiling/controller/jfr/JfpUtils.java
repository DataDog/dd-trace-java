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
package com.datadog.profiling.controller.jfr;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * Toolkit for working with .jfp files. A .jfp file is a .jfc file which has been transformed (using
 * the XSLT in the template-transformer project). It contains the same event settings as the
 * template, but in a format that is easier to handle in the profiling agent, not requiring us to
 * parse XML.
 */
public final class JfpUtils {
  public static final String DEFAULT_JFP = "jfr/dd.jfp";

  public static final String SAFEPOINTS_JFP = "jfr/safepoints.jfp";
  private static final String OVERRIDES_PATH = "jfr/overrides/";
  public static final String JFP_EXTENSION = ".jfp";

  private JfpUtils() {
    throw new UnsupportedOperationException("Toolkit!");
  }

  private static Map<String, String> readJfpFile(final InputStream stream) throws IOException {
    if (stream == null) {
      throw new IllegalArgumentException("Cannot read jfp file from empty stream!");
    }
    final Properties props = new Properties();
    props.load(stream);
    final Map<String, String> map = new HashMap<>();
    for (final Entry<Object, Object> o : props.entrySet()) {
      map.put(String.valueOf(o.getKey()), String.valueOf(o.getValue()));
    }
    return map;
  }

  private static InputStream getNamedResource(final String name) {
    return JfpUtils.class.getClassLoader().getResourceAsStream(name);
  }

  public static Map<String, String> readJfpResources(final String name, String overridesFileName)
      throws IOException {
    Map<String, String> result = readNamedJfpResource(name);
    result.putAll(readOverrideJfpResource(overridesFileName));
    return result;
  }

  public static Map<String, String> readNamedJfpResource(final String name) throws IOException {
    try (final InputStream stream = getNamedResource(name)) {
      return readJfpFile(stream);
    }
  }

  public static Map<String, String> readOverrideJfpResource(String name) throws IOException {
    if (name != null) {
      if (!name.toLowerCase(Locale.ROOT).endsWith(JFP_EXTENSION)) {
        name = name + JFP_EXTENSION;
      }
      final File file = new File(name);
      try (final InputStream stream =
          file.exists() ? new FileInputStream(file) : getNamedResource(OVERRIDES_PATH + name)) {
        if (stream == null) {
          throw new IOException("Invalid override file " + name);
        }
        return readJfpFile(stream);
      }
    }
    return new HashMap<>();
  }
}
