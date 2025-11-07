import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling
import jdk.jfr.FlightRecorder
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import spock.lang.Requires

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Collectors

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@Requires({
  !JavaVirtualMachine.isJ9()
})
class DirectAllocationTrackingTest extends InstrumentationSpecification {

  Recording recording
  Instant start

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.profiling.enabled", "true")
    injectSysConfig("dd.integration.mmap.enabled", "true")
    injectSysConfig("dd.profiling.directallocation.enabled", "true")
  }

  def "test track memory mapped file"() {
    setup:
    AtomicLong expectedSpanId = new AtomicLong()
    setupRecording()
    def file = File.createTempFile(getClass().getName() + "-" + UUID.randomUUID(), ".test")
    file.deleteOnExit()
    RandomAccessFile raf

    when:
    raf = new RandomAccessFile(file, "rw")
    runUnderTrace("context", {
      expectedSpanId.set(AgentTracer.activeSpan().getSpanId())
      raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 20)
    })
    def directAllocations = getDirectAllocations()

    then:
    directAllocations.size() == 2
    def sample = directAllocations.find({ it.getEventType().name.equals("datadog.DirectAllocationSample")})
    sample.getLong("allocated") == 20
    sample.getString("source") == "MMAP"
    sample.getString("allocatingClass") == "org.codehaus.groovy.vmplugin.v8.IndyInterface" // TODO: Groovy 4: "org.codehaus.groovy.runtime.callsite.PlainObjectMetaMethodSite"
    sample.getLong("spanId") == expectedSpanId.get()
    def total = directAllocations.find({ it.getEventType().name.equals("datadog.DirectAllocationTotal")})
    total.getLong("allocated") == 20
    total.getString("source") == "MMAP"
    total.getString("allocatingClass") == "org.codehaus.groovy.vmplugin.v8.IndyInterface" // TODO Groovy 4: "org.codehaus.groovy.runtime.callsite.PlainObjectMetaMethodSite"

    cleanup:
    recording.close()
    raf.close()
  }

  def "test track direct allocation"() {
    when:
    setupRecording()
    AtomicLong expectedSpanId = new AtomicLong()
    runUnderTrace("context", {
      expectedSpanId.set(AgentTracer.activeSpan().getSpanId())
      ByteBuffer.allocateDirect(10)
    })
    def directAllocations = getDirectAllocations()

    then:
    directAllocations.size() == 2
    def sample = directAllocations.find({ it.getEventType().name.equals("datadog.DirectAllocationSample")})
    sample.getLong("allocated") == 10
    sample.getString("source") == "ALLOCATE_DIRECT"
    sample.getString("allocatingClass") == "org.codehaus.groovy.vmplugin.v8.IndyInterface" // TODO: Groovy 4: "java_nio_ByteBuffer\$allocateDirect"
    sample.getLong("spanId") == expectedSpanId.get()
    def total = directAllocations.find({ it.getEventType().name.equals("datadog.DirectAllocationTotal")})
    total.getLong("allocated") == 10
    total.getString("source") == "ALLOCATE_DIRECT"
    total.getString("allocatingClass") == "org.codehaus.groovy.vmplugin.v8.IndyInterface"// TODO Groovy 4"java_nio_ByteBuffer\$allocateDirect"

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
    directAllocations.size() == 2
    def sample = directAllocations.find({ it.getEventType().name.equals("datadog.DirectAllocationSample")})
    sample.getLong("allocated") == 10
    def total = directAllocations.find({ it.getEventType().name.equals("datadog.DirectAllocationTotal")})
    total.getLong("allocated") == 10

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
      .filter({ ["datadog.DirectAllocationSample", "datadog.DirectAllocationTotal"].contains(it.getEventType().name)})
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
