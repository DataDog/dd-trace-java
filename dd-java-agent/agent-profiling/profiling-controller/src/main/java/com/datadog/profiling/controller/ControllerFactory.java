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

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.InvocationTargetException;

/** Factory used to get a {@link Controller}. */
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
  public static Controller createController(final ConfigProvider configProvider)
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
        return controller.getDeclaredConstructor(ConfigProvider.class).newInstance(configProvider);
      } catch (final ClassNotFoundException
          | NoSuchMethodException
          | InstantiationException
          | IllegalAccessException
          | InvocationTargetException e) {
        if (e.getCause() != null && e.getCause() instanceof ConfigurationException) {
          throw (ConfigurationException) e.getCause();
        }
        throw new UnsupportedEnvironmentException(getFixProposalMessage(), e);
      }
    }
    throw new UnsupportedEnvironmentException(getFixProposalMessage());
  }

  private static String getFixProposalMessage() {
    final String javaVendor = System.getProperty("java.vendor");
    final String javaVersion = System.getProperty("java.version");
    final String javaRuntimeName = System.getProperty("java.runtime.name");
    final String message =
        "Not enabling profiling for vendor="
            + javaVendor
            + ", version="
            + javaVersion
            + ", runtimeName="
            + javaRuntimeName;
    try {
      if (javaVersion == null) {
        return message;
      }
      if (javaVersion.startsWith("1.8")) {
        if (javaVendor.startsWith("Azul Systems")) {
          return message + "; it requires Zulu Java 8 (1.8.0_212+).";
        }
        if (javaVendor.startsWith("Oracle")) {
          if (javaRuntimeName.startsWith("OpenJDK")) {
            // this is a upstream build from openjdk docker repository for example
            return message + "; it requires 1.8.0_272+ OpenJDK builds (upstream)";
          } else {
            // this is a proprietary Oracle JRE/JDK 8
            return message + "; it requires Oracle JRE/JDK 8u40+";
          }
        }
        if (javaRuntimeName.startsWith("OpenJDK")) {
          return message
              + "; it requires 1.8.0_272+ OpenJDK builds from the following vendors: AdoptOpenJDK, Eclipse Temurin, Amazon Corretto, Azul Zulu, BellSoft Liberica.";
        }
      }
      return message + "; it requires OpenJDK 11+, Oracle Java 11+, or Zulu Java 8 (1.8.0_212+).";
    } catch (final Exception ex) {
      return message;
    }
  }
}
