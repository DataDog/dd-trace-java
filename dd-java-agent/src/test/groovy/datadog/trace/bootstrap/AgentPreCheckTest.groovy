package datadog.trace.bootstrap

import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class AgentPreCheckTest extends Specification {
  def 'parse java.version of #versionƒ as #expected'() {
    when:
    def major = AgentPreCheck.parseJavaMajorVersion(version)

    then:
    major == expected

    where:
    version      | expected
    null         | 0
    ''           | 0
    'a.0.0'      | 0
    '0.a.0'      | 0
    '0.0.a'      | 0
    '1.a.0_0'    | 1
    '1.6'        | 6
    '1.6.0_45'   | 6
    '1.7'        | 7
    '1.7.0'      | 7
    '1.7.0_221'  | 7
    '1.8.a_0'    | 8
    '1.8.0_a'    | 8
    '1.8'        | 8
    '1.8.0'      | 8
    '1.8.0_212'  | 8
    '1.8.0_292'  | 8
    '9-ea'       | 9
    '9.0.4'      | 9
    '9.1.2'      | 9
    '10.0.2'     | 10
    '11'         | 11
    '11a'        | 11
    '11.0.6'     | 11
    '11.0.11'    | 11
    '12.0.2'     | 12
    '13.0.2'     | 13
    '14'         | 14
    '14.0.2'     | 14
    '15'         | 15
    '15.0.2'     | 15
    '16.0.1'     | 16
    '11.0.9.1+1' | 11
    '11.0.6+10'  | 11
    '17.0.15'    | 17
    '21.0.7'     | 21
  }

  def 'log warning message when java is not compatible'() {
    setup:
    def output = new ByteArrayOutputStream()
    def logStream = new PrintStream(output)

    when:
    boolean compatible = AgentPreCheck.compatible(javaVersion, "/Library/$javaVersion", logStream)
    String log = output.toString()
    def logLines = log.isEmpty() ? [] : Arrays.asList(log.split('\n'))

    then:
    compatible == expectedCompatible

    if (expectedCompatible) {
      assert logLines.isEmpty()
    } else {
      logLines.size() == 2
      def expectedLogLines = [
        "Warning: Version ${AgentJar.getAgentVersion()} of dd-java-agent is not compatible with Java $javaVersion found at '/Library/$javaVersion' and will not be installed.",
        "Please upgrade your Java version to 8+"
      ]
      assert logLines == expectedLogLines
    }
    where:
    javaVersion | expectedCompatible
    null        | false
    ''          | false
    '1.6.0_45'  | false
    '1.7.0_221' | false
    '1.8.0_212' | true
    '11.0.6'    | true
    '17.0.15'   | true
    '21.0.7'    | true
  }

  def 'send hardcoded bootstrap telemetry for unsupported java'() {
    setup:
    Path path = Files.createTempFile('test-forwarder', '.sh', PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString('rwxr--r--')))
    File forwarderFile = path.toFile()
    forwarderFile.deleteOnExit()

    String forwarderPath = forwarderFile.getAbsolutePath()

    File outputFile = new File(forwarderPath + '.out')
    outputFile.deleteOnExit()

    def script = [
      '#!/usr/bin/env bash',
      'echo "$1	$(cat -)" >>' + outputFile.getAbsolutePath(),
      ''
    ]
    forwarderFile << script.join('\n')

    when:
    AgentPreCheck.sendTelemetry(forwarderPath, '1.6.0_45', '1.50')

    then:
    // Await completion of the external process handling the payload.
    new PollingConditions().within(5) {
      assert outputFile.exists()
    }
    String payload = outputFile.text

    String expectedPayload = '''
{
  "metadata": {
    "runtime_name": "jvm",
    "language_name": "jvm",
    "runtime_version": "1.6.0_45",
    "language_version": "1.6.0_45",
    "tracer_version": "1.50"
  },
  "points": [
    {
      "name": "library_entrypoint.abort",
      "tags": [
        "reason:incompatible_runtime"
      ]
    }
  ]
'''.replaceAll(/\s+/, '')

    // Assert that the actual payload contains the expected data.
    payload.contains(expectedPayload)
  }

  def 'check #clazz compiled with Java #javaVersion'() {
    setup:
    def resource = clazz.getName().replace('.', '/') + '.class'
    def stream = new DataInputStream(this.getClass().getClassLoader().getResourceAsStream(resource))

    expect:
    stream.withCloseable {
      def magic = Integer.toUnsignedLong(it.readInt())
      def minor = (int) it.readShort()
      def major = (int) it.readShort()

      assert magic == 0xCAFEBABEL
      assert minor == 0
      assert major == expectedMajor
    } == null

    where:
    clazz          | javaVersion | expectedMajor
    AgentPreCheck  | 6           | 50
    AgentBootstrap | 8           | 52
  }
}
