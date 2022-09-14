import datadog.trace.agent.test.AgentTestRunner
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
    injectSysConfig("dd.integration.directbytebuffer.enabled", "true")
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
    directAllocations.get(0).getInt("capacity") == 20

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
    directAllocations.get(0).getInt("capacity") == 10

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
    directAllocations.get(0).getInt("capacity") == 10

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
      .filter({ it.getEventType().name.equals("datadog.DirectAllocationEvent")})
      .collect(Collectors.toList())
  }

  def setupRecording() {
    recording = new Recording()
    recording.enable("datadog.DirectAllocationEvent")
    recording.start()
    start = Instant.now()
  }
}
