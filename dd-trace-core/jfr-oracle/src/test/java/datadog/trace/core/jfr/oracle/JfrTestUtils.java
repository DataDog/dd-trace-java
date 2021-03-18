/*
 * Copyright 2018 The OpenTracing Authors
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
package datadog.trace.core.jfr.oracle;

import static java.util.Objects.nonNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import oracle.jrockit.jfr.JFR;
import oracle.jrockit.jfr.parser.ChunkParser;
import oracle.jrockit.jfr.parser.FLREvent;
import oracle.jrockit.jfr.parser.Parser;

@SuppressWarnings("deprecation")
public final class JfrTestUtils {
  private static final String RECORDING_NAME = "testing-jfr";

  public static final class Recording {
    private final Path path;

    Recording(Path path) {
      this.path = path;
    }

    public List<?> stop() throws IOException {
      try {
        try {
          MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
          mbs.invoke(
              new ObjectName("com.sun.management:type=DiagnosticCommand"),
              "jfrStop",
              new Object[] {
                new String[] {
                  "name=" + RECORDING_NAME, "filename=" + path.toAbsolutePath().toString()
                }
              },
              new String[] {String[].class.getName()});
        } catch (InstanceNotFoundException
            | MBeanException
            | MalformedObjectNameException
            | ReflectionException ex) {
          fail(ex.getMessage());
        }

        try (Parser parser = new Parser(path.toFile())) {
          List<FLREvent> readAllEvents = new ArrayList<>();
          for (ChunkParser chunkParser : parser) {
            for (FLREvent event : chunkParser) {
              if (event.getPath().startsWith("datadog")) {
                readAllEvents.add(event);
              }
            }
          }
          return readAllEvents;
        }
      } finally {
        try {
          Files.delete(path);
        } catch (Throwable t) {
          // Should not affect test...
          System.err.println("Failed to delete test JFR-file: " + t.getMessage());
        }
      }
    }
  }

  private JfrTestUtils() {}

  private static Path getJfrConfig() throws IOException {
    Path jfrConfig = Files.createTempFile("testing", ".jfc");
    Files.copy(
        JfrTestUtils.class.getResourceAsStream("/testing.jfc"),
        jfrConfig,
        StandardCopyOption.REPLACE_EXISTING);
    return jfrConfig;
  }

  public static Recording startRecording() {
    Path jfrConfig = null;
    try {
      jfrConfig = getJfrConfig();
    } catch (IOException ex) {
      fail(ex.getMessage());
    }

    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    try {
      mbs.invoke(
          new ObjectName("com.sun.management:type=DiagnosticCommand"),
          "vmUnlockCommercialFeatures",
          new Object[0],
          new String[0]);
      mbs.invoke(
          new ObjectName("com.sun.management:type=DiagnosticCommand"),
          "jfrStart",
          new Object[] {
            new String[] {
              "name=" + RECORDING_NAME, "settings=" + jfrConfig.toAbsolutePath().toString()
            }
          },
          new String[] {String[].class.getName()});

      assertTimeout(
          Duration.ofSeconds(10),
          () -> {
            while (JFR.get().getMBean().getRecordings().isEmpty()) {
              System.out.println("Waiting for recording to start");
              Thread.sleep(10);
            }
          });
      return new Recording(Files.createTempFile("jfr-test", ".jfr"));
    } catch (InstanceNotFoundException
        | MBeanException
        | MalformedObjectNameException
        | ReflectionException
        | IOException ex) {
      fail(ex.getMessage());
    } finally {
      if (nonNull(jfrConfig)) {
        try {
          Files.delete(jfrConfig);
        } catch (IOException ex) {
        }
      }
    }
    return null;
  }
}
