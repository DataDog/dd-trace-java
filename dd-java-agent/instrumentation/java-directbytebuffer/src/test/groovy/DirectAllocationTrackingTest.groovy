import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling
import jdk.jfr.FlightRecorder
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.time.Instant
import java.util.stream.Collectors

class DirectAllocationTrackingTest extends AgentTestRunner {

  Recording recording
  Instant start

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.profiling.enabled", "true")
    injectSysConfig("dd.integration.mmap.enabled", "true")
    injectSysConfig("dd.integration.allocatedirect.enabled", "true")
  }

  def "test track memory mapped file"() {
    setup:
    setupRecording()
    def file = File.createTempFile(getClass().getName() + "-" + UUID.randomUUID(), ".test")
    file.deleteOnExit()
    RandomAccessFile raf

    when:
    raf = new RandomAccessFile(file, "rw")
    raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 20)
    def directAllocations = getDirectAllocations()

    then:
    directAllocations.size() == 1
    directAllocations.get(0).getLong("allocated") == 20
    directAllocations.get(0).getString("source") == "MMAP"
    directAllocations.get(0).getString("allocatingClass") == "org.codehaus.groovy.runtime.callsite.PlainObjectMetaMethodSite"

    cleanup:
    recording.close()
    raf.close()
  }

  def "test track direct allocation"() {
    when:
    setupRecording()
    ByteBuffer.allocateDirect(10)
    def directAllocations = getDirectAllocations()

    then:
    directAllocations.size() == 1
    directAllocations.get(0).getLong("allocated") == 10
    directAllocations.get(0).getString("source") == "ALLOCATE_DIRECT"
    directAllocations.get(0).getString("allocatingClass") == "java_nio_ByteBuffer\$allocateDirect"

    cleanup:
    recording.close()
  }

  def "test slices and duplicates aren't instrumented"() {
    when:
    setupRecording()
    ByteBuffer buffer = ByteBuffer.allocateDirect(10)
    buffer.slice()
    buffer.duplicate()
    def directAllocations = getDirectAllocations()

    then:
    directAllocations.size() == 1
    directAllocations.get(0).getLong("allocated") == 10

    cleanup:
    recording.close()
  }

  def getDirectAllocations() {
    def snapshot = FlightRecorder.getFlightRecorder().takeSnapshot()
    def stream = snapshot.getStream(start, Instant.now())
    File output = Files.createTempFile("recording", ".jfr").toFile()
    output.deleteOnExit()
    stream.transferTo(new FileOutputStream(output))
    return RecordingFile.readAllEvents(output.toPath())
      .stream()
      .filter({ it.getEventType().name.equals("datadog.DirectAllocationSample")})
      .collect(Collectors.toList())
  }

  def setupRecording() {
    recording = new Recording()
    recording.enable("datadog.DirectAllocationSample")
    recording.enable("datadog.DirectAllocationTotal")
    recording.start()
    start = Instant.now()
    InstrumentationBasedProfiling.enableInstrumentationBasedProfiling()
  }
}
