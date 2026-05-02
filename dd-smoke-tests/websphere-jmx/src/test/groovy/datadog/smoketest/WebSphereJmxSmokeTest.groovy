package datadog.smoketest


import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import spock.lang.Shared

/**
 * Smoke test for the websphere-jmx instrumentation.
 *
 * Builds and starts a WebSphere traditional (tWAS) container with the dd-java-agent baked in
 * via jvm-config.props, then verifies that jmxfetch reports WebSphere thread pool metrics
 * via statsd UDP.
 *
 * Note that the websphere related metrics will only arrive if our instrumentation is applied.
 */
class WebSphereJmxSmokeTest extends AbstractSmokeTest {

  private static final Logger LOG = LoggerFactory.getLogger(WebSphereJmxSmokeTest)

  @Override
  protected int numberOfProcesses() {
    return 0
  }

  @Shared
  DatagramSocket statsdSocket

  @Shared
  int statsdPort

  @Shared
  BlockingQueue<String> statsdMessages = new ArrayBlockingQueue<>(256)

  @Shared
  Thread listenerThread

  @Shared
  GenericContainer websphere

  def setupSpec() {
    statsdSocket = new DatagramSocket()
    statsdPort = statsdSocket.getLocalPort()
    LOG.info("StatsDServer listening on UDP port {}", statsdPort)

    listenerThread = Thread.start {
      byte[] buf = new byte[2048]
      while (!Thread.currentThread().interrupted()) {
        try {
          DatagramPacket packet = new DatagramPacket(buf, buf.length)
          statsdSocket.receive(packet)
          String msg = new String(packet.getData(), 0, packet.getLength())
          LOG.debug("Received statsd: {}", msg)
          statsdMessages.offer(msg, 1, TimeUnit.SECONDS)
        } catch (Exception ignored) {
          break
        }
      }
    }

    websphere = new GenericContainer("icr.io/appcafe/websphere-traditional:latest")
      // inject wished jvm props for the server we are running
      .withCopyFileToContainer(MountableFile.forClasspathResource("jvm-config.props"), "/work/config/")
      // copy the agent jar
      .withCopyFileToContainer(MountableFile.forHostPath(shadowJarPath), "/opt/dd-java-agent.jar")
      // let it run on a macos for dev
      .withCreateContainerCmdModifier { it.withPlatform('linux/amd64') }
      // this is required to send back udp datagrams to us
      .withExtraHost('host.docker.internal', 'host-gateway')
      // set jmxfetch props
      .withEnv('DD_JMXFETCH_STATSD_HOST', 'host.docker.internal')
      .withEnv('DD_JMXFETCH_STATSD_PORT', String.valueOf(statsdPort))
      .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix('websphere'))
      // the server will restart 2 times. First to update the jvm props
      .waitingFor(Wait.forLogMessage('.*open for e-business.*', 2))
      // it can be long
      .withStartupTimeout(Duration.ofMinutes(8))
      // override the command (by default it's /work/start_server.sh)
      .withCommand("bash", "-c", "/work/configure.sh && /work/start_server.sh")

    websphere.start()
    LOG.info("WebSphere container started")
  }

  def cleanupSpec() {
    websphere?.stop()
    statsdSocket?.close()
    listenerThread?.join()
  }

  def "jmxfetch reports WebSphere thread pool metrics via statsd"() {
    when: "waiting for websphere.thread_pool metrics"
    String metric = waitForMetric('websphere.thread_pool', Duration.ofMinutes(3))

    then: "at least one thread pool metric arrives"
    metric != null
    metric.contains('websphere.thread_pool')
  }

  def waitForMetric(String prefix, Duration timeout) {
    long deadline = System.currentTimeMillis() + timeout.toMillis()
    while (System.currentTimeMillis() < deadline) {
      String msg = statsdMessages.poll(5, TimeUnit.SECONDS)
      if (msg?.contains(prefix)) {
        return msg
      }
    }
    return null
  }
}
