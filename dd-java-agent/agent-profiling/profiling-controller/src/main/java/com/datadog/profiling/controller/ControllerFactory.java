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
package com.datadog.profiling.controller;

import datadog.trace.api.Config;
import java.lang.reflect.InvocationTargetException;
import lombok.extern.slf4j.Slf4j;

/** Factory used to get a {@link Controller}. */
@Slf4j
public final class ControllerFactory {

  /**
   * Returns the created controller.
   *
   * @return the created controller.
   * @throws UnsupportedEnvironmentException if there is controller available for the platform we're
   *     running in. See the exception message for specifics.
   * @throws ConfigurationException if profiler cannot start due to configuration problems
   */
  public static Controller createController(final Config config)
      throws UnsupportedEnvironmentException, ConfigurationException {
    try {
      Class.forName("com.oracle.jrockit.jfr.Producer");
      throw new UnsupportedEnvironmentException(
          "Not enabling profiling; it requires Oracle Java 11+.");
    } catch (final ClassNotFoundException e) {
      // Fall through - until we support Oracle JDK 7 & 8, this is a good thing. ;)
    }
    try {
      final Class<? extends Controller> clazz =
          Class.forName("com.datadog.profiling.controller.openjdk.OpenJdkController")
              .asSubclass(Controller.class);
      return clazz.getDeclaredConstructor(Config.class).newInstance(config);
    } catch (final ClassNotFoundException
        | NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | InvocationTargetException e) {
      if (e.getCause() != null && e.getCause() instanceof ConfigurationException) {
        throw (ConfigurationException) e.getCause();
      }
      final String message = "Not enabling profiling" + getFixProposalMessage();
      throw new UnsupportedEnvironmentException(message, e);
    }
  }

  private static String getFixProposalMessage() {
    try {
      final String javaVersion = System.getProperty("java.version");
      if (javaVersion == null) {
        return "";
      }
      final String javaVendor = System.getProperty("java.vendor", "");
      if (javaVersion.startsWith("1.8")) {
        if (javaVendor.startsWith("Azul Systems")) {
          return "; it requires Zulu Java 8 (1.8.0_212+).";
        }
        String javaRuntimeName = System.getProperty("java.runtime.name", "");
        if (javaVendor.startsWith("Oracle") && javaRuntimeName.startsWith("OpenJDK")) {
          // this is a upstream build from openjdk docker repository for example
          return "; it requires 1.8.0_272+ OpenJDK builds (upstream)";
        }
        if (javaRuntimeName.startsWith("OpenJDK")) {
          return "; it requires 1.8.0_262+ OpenJDK builds from the following vendors: AdoptOpenJDK, Amazon Corretto, Azul Zulu, BellSoft Liberica";
        }
      }
      return "; it requires OpenJDK 11+, Oracle Java 11+, or Zulu Java 8 (1.8.0_212+).";
    } catch (final Exception ex) {
      return "";
    }
  }
}
