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
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.InvocationTargetException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Factory used to get a {@link Controller}. */
public final class ControllerFactory {
  private static final Logger log = LoggerFactory.getLogger(ControllerFactory.class);

  private static enum Implementation {
    NONE(),
    ORACLE("com.datadog.profiling.controller.oracle.OracleJdkController"),
    OPENJDK("com.datadog.profiling.controller.openjdk.OpenJdkController"),
    ASYNC("com.datadog.profiling.controller.ddprof.DatadogProfilerController");

    private final String className;

    Implementation() {
      this.className = null;
    }

    Implementation(String className) {
      this.className = className;
    }

    String className() {
      return className;
    }
  }

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
    Implementation impl = Implementation.NONE;
    boolean isOracleJDK8 = Platform.isOracleJDK8();
    boolean isDatadogProfilerEnabled = Config.get().isDatadogProfilerEnabled();
    if (isOracleJDK8 && !isDatadogProfilerEnabled) {
      try {
        Class.forName("com.oracle.jrockit.jfr.Producer");
        impl = Implementation.ORACLE;
      } catch (ClassNotFoundException ignored) {
        log.debug("Failed to load oracle profiler", ignored);
      }
    }
    if (!isOracleJDK8) {
      try {
        Class.forName("jdk.jfr.Event");
        impl = Implementation.OPENJDK;
      } catch (ClassNotFoundException ignored) {
        log.debug("Failed to load openjdk profiler", ignored);
      }
    }
    if (impl == Implementation.NONE) {
      if ((Platform.isLinux() || Platform.isMac()) && isDatadogProfilerEnabled) {
        try {
          Class<?> datadogProfilerClass =
              Class.forName("com.datadog.profiling.ddprof.DatadogProfiler");
          if ((boolean)
              datadogProfilerClass
                  .getMethod("isAvailable")
                  .invoke(datadogProfilerClass.getMethod("getInstance").invoke(null))) {
            impl = Implementation.ASYNC;
          } else {
            log.debug("Failed to load Datadog profiler, it is not available");
          }
        } catch (final ClassNotFoundException
            | NoSuchMethodException
            | IllegalAccessException
            | InvocationTargetException ignored) {
          log.debug("Failed to load Datadog profiler", ignored);
        }
      }
    }
    if (impl == Implementation.NONE) {
      throw new UnsupportedEnvironmentException(getFixProposalMessage());
    }

    try {
      log.debug("Trying to load {}", impl.className());
      return Class.forName(impl.className())
          .asSubclass(Controller.class)
          .getDeclaredConstructor(ConfigProvider.class)
          .newInstance(configProvider);
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
