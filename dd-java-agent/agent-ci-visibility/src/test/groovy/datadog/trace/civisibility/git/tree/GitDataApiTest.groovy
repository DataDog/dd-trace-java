package datadog.trace.civisibility.git.tree

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

import com.squareup.moshi.Moshi
import datadog.communication.BackendApi
import datadog.communication.EvpProxyApi
import datadog.communication.http.HttpRetryPolicy
import datadog.communication.http.HttpUtils
import datadog.http.client.HttpClient
import datadog.http.client.HttpUrl
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector
import datadog.trace.test.util.MultipartRequestParser
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

class GitDataApiTest extends Specification {

  @Shared
  Moshi moshi = new Moshi.Builder().build()

  @Shared
  @AutoCleanup
  TestHttpServer intakeServer = httpServer {
    handlers {
      prefix("/api/v2/git/repository/search_commits") {
        def requestJson = moshi.adapter(Map).fromJson(new String(request.body))
        boolean expectedRequest = requestJson == [
          "meta": [
            "repository_url": "gitRemoteUrl"
          ],
          "data": [
            ["id": "sha1", "type": "commit"],
            ["id": "sha2", "type": "commit"]
          ]
        ]

        if (expectedRequest) {
          response.status(200).send('{"data": [{ "id": "sha2", "type": "commit" }] }')
        } else {
          response.status(400).send()
        }
      }

      prefix("/api/v2/git/repository/packfile") {
        def parsed = MultipartRequestParser.parseRequest(request.body, request.headers.get("Content-Type"))
        def pushedShas = parsed.get("pushedSha")
        def packfiles = parsed.get("packfile")

        boolean expectedRequest = true

        expectedRequest &= pushedShas.size() == 1
        expectedRequest &= packfiles.size() == 1

        def pushedSha = pushedShas.iterator().next()
        def packFile = packfiles.iterator().next()

        def parsedPushedSha = moshi.adapter(Map).fromJson(new String(pushedSha.get()))
        expectedRequest &= parsedPushedSha == [
          "meta": [
            "repository_url": "gitRemoteUrl"
          ],
          "data": [
            "id"  : "sha1",
            "type": "commit"
          ]
        ]

        expectedRequest &= new String(packFile.get()) == "pack file contents"

        if (expectedRequest) {
          response.status(200).send()
        } else {
          response.status(400).send()
        }
      }
    }
  }

  @Shared
  @TempDir
  Path tempDir

  public static final int REQUEST_TIMEOUT_MILLIS = 15_000

  def "test commits search"() {
    given:
    def metricCollector = Stub(CiVisibilityMetricCollector)
    def evpProxy = givenEvpProxy()

    when:
    def gitDataApi = new GitDataApi(evpProxy, metricCollector)
    def commits = new ArrayList<>(gitDataApi.searchCommits("gitRemoteUrl", ["sha1", "sha2"]))

    then:
    commits == ["sha2"]
  }

  def "test pack file upload"() {
    given:
    def metricCollector = Stub(CiVisibilityMetricCollector)
    def evpProxy = givenEvpProxy()
    def packFile = givenPackFile()

    when:
    def gitDataApi = new GitDataApi(evpProxy, metricCollector)
    gitDataApi.uploadPackFile("gitRemoteUrl", "sha1", packFile)

    then:
    1 == 1 // if mock server does not receive expected request, tested method will throw an exception
  }

  private BackendApi givenEvpProxy() {
    String traceId = "a-trace-id"
    HttpUrl proxyUrl = HttpUrl.from(intakeServer.address)
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0)
    HttpClient client = HttpUtils.buildHttpClient(proxyUrl, REQUEST_TIMEOUT_MILLIS)
    return new EvpProxyApi(traceId, proxyUrl, "api", retryPolicyFactory, client, true)
  }

  private Path givenPackFile() {
    def packFile = tempDir.resolve("packFile")
    Files.write(packFile, "pack file contents".bytes)
    return packFile
  }
}
