package datadog.smoketest

import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Shared

class SpringBootMongoIntegrationTest extends AbstractServerSmokeTest {

  @Shared
  def mongoDbContainer

  @Shared
  String mongoDbUri

  def cleanupSpec() {
    if (mongoDbContainer) {
      mongoDbContainer.stop()
    }
  }

  @Override
  void beforeProcessBuilders() {
    mongoDbContainer = new MongoDBContainer(DockerImageName.parse("mongo:4.0.10"))
    mongoDbContainer.start()
    mongoDbUri = mongoDbContainer.replicaSetUrl
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=TraceStructureWriter:${output.getAbsolutePath()}",
      "-jar",
      springBootShadowJar,
      "--server.port=${httpPort}"
    ])
    if (mongoDbUri) {
      command.add("--spring.data.mongodb.uri=$mongoDbUri".toString())
    }
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  File createTemporaryFile() {
    return File.createTempFile("trace-structure-docs", "out")
  }

  @Override
  protected Set<String> expectedTraces() {
    return ["[servlet.request[spring.handler[repository.operation[mongo.query]]]]"]
  }

  def "put docs and find all docs"() {
    setup:
    String url = "http://localhost:${httpPort}/docs"
    MediaType plainText = MediaType.parse("text/plain; charset=utf-8")
    client.newCall(new Request.Builder().url(url).post(RequestBody.create(plainText, "foo")).build()).execute()
    client.newCall(new Request.Builder().url(url).post(RequestBody.create(plainText, "bar")).build()).execute()

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("foo")
    responseBodyStr.contains("bar")
  }
}
