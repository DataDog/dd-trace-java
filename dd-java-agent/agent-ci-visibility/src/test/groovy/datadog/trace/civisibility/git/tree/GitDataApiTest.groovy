package datadog.trace.civisibility.git.tree

import com.squareup.moshi.Moshi
import datadog.communication.http.HttpRetryPolicy
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.civisibility.communication.BackendApi
import datadog.trace.civisibility.communication.EvpProxyApi
import datadog.trace.test.util.MultipartRequestParser
import okhttp3.HttpUrl
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

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
    def evpProxy = givenEvpProxy()

    when:
    def gitDataApi = new GitDataApi(evpProxy)
    def commits = new ArrayList<>(gitDataApi.searchCommits("gitRemoteUrl", ["sha1", "sha2"]))

    then:
    commits == ["sha2"]
  }

  def "test pack file upload"() {
    given:
    def evpProxy = givenEvpProxy()
    def packFile = givenPackFile()

    when:
    def gitDataApi = new GitDataApi(evpProxy)
    gitDataApi.uploadPackFile("gitRemoteUrl", "sha1", packFile)

    then:
    1 == 1 // if mock server does not receive expected request, tested method will throw an exception
  }

  private BackendApi givenEvpProxy() {
    HttpUrl proxyUrl = HttpUrl.get(intakeServer.address)
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0)
    return new EvpProxyApi(proxyUrl, REQUEST_TIMEOUT_MILLIS, retryPolicyFactory)
  }

  private Path givenPackFile() {
    def packFile = tempDir.resolve("packFile")
    Files.write(packFile, "pack file contents".bytes)
    return packFile
  }
}
