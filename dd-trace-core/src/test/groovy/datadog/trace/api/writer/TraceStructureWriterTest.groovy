package datadog.trace.api.writer


import datadog.trace.common.writer.TraceStructureWriter
import datadog.trace.core.test.DDCoreSpecification

class TraceStructureWriterTest extends DDCoreSpecification {
  def "parse CLI args"() {
    when:
    def args = TraceStructureWriter.parseArgs(cli, windows)

    then:
    args.length > 0
    args[0] == path

    where:
    windows | cli                             | path
    true    | 'C:/tmp/file'                   | 'C:/tmp/file'
    true    | 'C:\\tmp\\file'                 | 'C:\\tmp\\file'
    true    | 'file'                          | 'file'
    true    | 'C:/tmp/file:includeresource'   | 'C:/tmp/file'
    true    | 'C:\\tmp\\file:includeresource' | 'C:\\tmp\\file'
    true    | 'file:includeresource'          | 'file'
    false   | '/var/tmp/file'                 | '/var/tmp/file'
    false   | 'file'                          | 'file'
    false   | '/var/tmp/file'                 | '/var/tmp/file'
    false   | 'file'                          | 'file'
  }
}
