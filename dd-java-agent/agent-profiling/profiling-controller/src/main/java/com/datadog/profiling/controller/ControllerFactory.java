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
import de.thetaphi.forbiddenapis.SuppressForbidden;
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
  @SuppressForbidden
  public static Controller createController(final Config config)
      throws UnsupportedEnvironmentException, ConfigurationException {
    boolean isOracleJfr = false;
    boolean isOpenJdkJfr = false;
    try {
      Class.forName("com.oracle.jrockit.jfr.Producer");
      isOracleJfr = true;
    } catch (ClassNotFoundException ignored) {
      // expected
    }
    if (!isOracleJfr) {
      try {
        Class.forName("jdk.jfr.Event");
        isOpenJdkJfr = true;
      } catch (ClassNotFoundException ignored) {
        // expected
      }
    }
    if (isOracleJfr || isOpenJdkJfr) {
      try {

        final Class<? extends Controller> controller =
            Class.forName(
                    isOracleJfr
                        ? "com.datadog.profiling.controller.oracle.OracleJdkController"
                        : "com.datadog.profiling.controller.openjdk.OpenJdkController")
                .asSubclass(Controller.class);
        return controller.getDeclaredConstructor(Config.class).newInstance(config);
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
    throw new UnsupportedEnvironmentException("Not enabling profiling" + getFixProposalMessage());
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
        final String javaRuntimeName = System.getProperty("java.runtime.name", "");
        if (javaVendor.startsWith("Oracle")) {
          if (javaRuntimeName.startsWith("OpenJDK")) {
            // this is a upstream build from openjdk docker repository for example
            return "; it requires 1.8.0_272+ OpenJDK builds (upstream)";
          } else {
            // this is a proprietary Oracle JRE/JDK 8
            return "; it requires Oracle JRE/JDK 8u40+";
          }
        }
        if (javaRuntimeName.startsWith("OpenJDK")) {
          return "; it requires 1.8.0_272+ OpenJDK builds from the following vendors: AdoptOpenJDK, Amazon Corretto, Azul Zulu, BellSoft Liberica";
        }
      }
      return "; it requires OpenJDK 11+, Oracle Java 11+, or Zulu Java 8 (1.8.0_212+).";
    } catch (final Exception ex) {
      return "";
    }
  }
}
