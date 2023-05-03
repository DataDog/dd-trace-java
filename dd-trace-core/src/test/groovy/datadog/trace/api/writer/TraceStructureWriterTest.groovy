package datadog.trace.api.writer


import datadog.trace.common.writer.TraceStructureWriter
import datadog.trace.core.test.DDCoreSpecification

class TraceStructureWriterTest extends DDCoreSpecification {
  def "parse CLI args"() {
    when:
    System.setProperty("os.name", osName)
    def args = TraceStructureWriter.parseArgs(cli)

    then:
    args.length > 0
    args[0] == path

    where:
    osName    | cli                             | path
    'Windows' | 'C:/tmp/file'                   | 'C:/tmp/file'
    'Windows' | 'C:\\tmp\\file'                 | 'C:\\tmp\\file'
    'Windows' | 'file'                          | 'file'
    'Windows' | 'C:/tmp/file:includeresource'   | 'C:/tmp/file'
    'Windows' | 'C:\\tmp\\file:includeresource' | 'C:\\tmp\\file'
    'Windows' | 'file:includeresource'          | 'file'
    'Linux'   | '/var/tmp/file'                 | '/var/tmp/file'
    'Linux'   | 'file'                          | 'file'
    'MacOS'   | '/var/tmp/file'                 | '/var/tmp/file'
    'MacOS'   | 'file'                          | 'file'
  }
}
