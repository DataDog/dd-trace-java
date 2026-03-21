package datadog.trace.api.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.common.writer.TraceStructureWriter;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class TraceStructureWriterTest {

  @TableTest({
    "scenario                              | windows | cli                             | path           ",
    "windows absolute with forward slashes | true    | 'C:/tmp/file'                   | 'C:/tmp/file'  ",
    "windows absolute with backslashes     | true    | 'C:\\tmp\\file'                 | 'C:\\tmp\\file'",
    "windows relative                      | true    | 'file'                          | 'file'         ",
    "windows absolute with option          | true    | 'C:/tmp/file:includeresource'   | 'C:/tmp/file'  ",
    "windows backslash with option         | true    | 'C:\\tmp\\file:includeresource' | 'C:\\tmp\\file'",
    "windows relative with option          | true    | 'file:includeresource'          | 'file'         ",
    "linux absolute                        | false   | '/var/tmp/file'                 | '/var/tmp/file'",
    "linux relative                        | false   | 'file'                          | 'file'         ",
    "linux absolute 2                      | false   | '/var/tmp/file'                 | '/var/tmp/file'",
    "linux relative 2                      | false   | 'file'                          | 'file'         "
  })
  @ParameterizedTest(name = "[{index}] parse CLI args")
  void parseCliArgs(boolean windows, String cli, String path) {
    String[] args = TraceStructureWriter.parseArgs(cli, windows);

    assertTrue(args.length > 0);
    assertEquals(path, args[0]);
  }
}
