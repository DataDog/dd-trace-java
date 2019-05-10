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

import com.datadog.profiling.controller.openjdk.OpenJdkController;

/** Factory used to get a {@link Controller}. */
public final class ControllerFactory {

  /**
   * Returns the created controller.
   *
   * @return the created controller.
   * @throws UnsupportedEnvironmentException if there is controller available for the platform we're
   *     running in. See the exception message for specifics.
   */
  public static final Controller createController() throws UnsupportedEnvironmentException {
    try {
      Class.forName("com.oracle.jrockit.jfr.Producer");
      throw new UnsupportedEnvironmentException(
          "The JFR controller is currently not supported on the Oracle JDK <= JDK 11!");
    } catch (ClassNotFoundException e) {
      // Fall through - until we support Oracle JDK 7 & 8, this is a good thing. ;)
    }
    try {
      Class.forName("jdk.jfr.Event");
    } catch (ClassNotFoundException e) {
      throw new UnsupportedEnvironmentException(
          "The JFR controller could not find a supported JFR API");
    }
    return new OpenJdkController();
  }
}
