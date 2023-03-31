package datadog.trace.instrumentation.java.jfr

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling
import jdk.jfr.FlightRecorder
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile

import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Collectors

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class JfrEventInstrumentationTest extends AgentTestRunner {

  Recording recording
  Instant start

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.profiling.enabled", "true")
    injectSysConfig("dd.integration.jfr.enabled", "true")
  }

  def "test apply context to file writes"() {
    setup:
    AtomicLong expectedRootSpanId = new AtomicLong()
    AtomicLong expectedSpanId = new AtomicLong()
    setupRecording()
    def file = File.createTempFile(getClass().getName() + "-" + UUID.randomUUID(), ".test")
    file.deleteOnExit()
    RandomAccessFile raf

    when:
    raf = new RandomAccessFile(file, "rw")
    runUnderTrace("root", {
      expectedRootSpanId.set(AgentTracer.activeSpan().getSpanId())
      runUnderTrace("span", {
        expectedSpanId.set(AgentTracer.activeSpan().getSpanId())
        byte[] data = new byte[1024]
        ThreadLocalRandom.current().nextBytes(data)
        raf.write(data)
      })
    })
    def fileWrites = getFileWrites()

    then:
    fileWrites.size() == 1
    def sample = fileWrites.find({ it.getEventType().name.equals("jdk.FileWrite")})
    sample.getLong("rootSpanId") == expectedRootSpanId.get()
    sample.getLong("spanId") == expectedSpanId.get()

    cleanup:
    recording.close()
    raf.close()
  }

  def getFileWrites() {
    def snapshot = FlightRecorder.getFlightRecorder().takeSnapshot()
    def stream = snapshot.getStream(start, Instant.now())
    File output = Files.createTempFile("recording", ".jfr").toFile()
    output.deleteOnExit()
    stream.transferTo(new FileOutputStream(output))
    return RecordingFile.readAllEvents(output.toPath())
      .stream()
      .filter({ it.getEventType().name == "jdk.FileWrite"})
      .collect(Collectors.toList())
  }

  def setupRecording() {
    recording = new Recording()
    recording.enable("jdk.FileWrite")
    recording.setSettings(["jdk.FileWrite#threshold": "0 ms"])
    recording.start()
    start = Instant.now()
    InstrumentationBasedProfiling.enableInstrumentationBasedProfiling()
  }
}
