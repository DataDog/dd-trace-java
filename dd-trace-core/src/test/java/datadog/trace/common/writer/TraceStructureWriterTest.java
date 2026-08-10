package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.core.DDCoreJavaSpecification;
import org.tabletest.junit.TableTest;

public class TraceStructureWriterTest extends DDCoreJavaSpecification {

  @TableTest({
    "scenario                           | windows | cli                           | path         ",
    "windows path                       | true    | C:/tmp/file                   | C:/tmp/file  ",
    "windows backslash path             | true    | C:\\tmp\\file                 | C:\\tmp\\file",
    "windows file                       | true    | file                          | file         ",
    "windows path with option           | true    | C:/tmp/file:includeresource   | C:/tmp/file  ",
    "windows backslash path with option | true    | C:\\tmp\\file:includeresource | C:\\tmp\\file",
    "windows file with option           | true    | file:includeresource          | file         ",
    "unix absolute path 1               | false   | /var/tmp/file                 | /var/tmp/file",
    "unix file 1                        | false   | file                          | file         ",
    "unix absolute path 2               | false   | /var/tmp/file                 | /var/tmp/file",
    "unix file 2                        | false   | file                          | file         "
  })
  void parseCliArgs(boolean windows, String cli, String path) {
    String[] args = TraceStructureWriter.parseArgs(cli, windows);

    assertTrue(args.length > 0);
    assertEquals(path, args[0]);
  }
}
