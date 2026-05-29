package datadog.gradle.plugin.sca

import datadog.gradle.sca.GhsaEnrichmentParser
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URL
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers the [generateScaCvesJson] task that downloads GHSA enrichments from
 * `sca-reachability-database` and generates `sca_cves.json` bundled in the appsec JAR.
 *
 * This is a **temporary** build-time approach. The symbol database will be delivered
 * via Remote Config in a future iteration, at which point this plugin and the committed
 * `sca_cves.json` file will be removed.
 *
 * Usage: `apply plugin: 'dd-trace-java.sca-enrichments'`. The task runs only when
 * `-PrefreshSca` is passed or the output file is absent; CI uses the committed copy.
 */
@Suppress("unused")
class ScaEnrichmentsPlugin : Plugin<Project> {

  companion object {
    private const val SCA_ENRICHMENTS_API =
        "https://api.github.com/repos/DataDog/sca-reachability-database/contents/enrichments"
  }

  override fun apply(project: Project) {
    val outputFile = project.file("src/main/resources/sca_cves.json")

    val generateTask =
        project.tasks.register("generateScaCvesJson") {
          description =
              "Downloads GHSA enrichments from sca-reachability-database and updates " +
                  "src/main/resources/sca_cves.json. Run with -PrefreshSca to force a refresh. " +
                  "sca_cves.json is committed to the repo so CI does not need network access."
          group = "build"
          outputs.file(outputFile)
          onlyIf { project.hasProperty("refreshSca") || !outputFile.exists() }

          doLast {
            val token = System.getenv("GITHUB_TOKEN")

            logger.lifecycle("Fetching GHSA enrichment index from GitHub...")
            @Suppress("UNCHECKED_CAST")
            val fileList = githubFetch(SCA_ENRICHMENTS_API, token) as List<Map<String, Any>>
            val ghsaFiles =
                fileList.filter {
                  it["name"]?.toString()?.endsWith(".json") == true && it["type"] == "file"
                }
            logger.lifecycle("Found ${ghsaFiles.size} enrichment files")

            val entries = mutableListOf<Any>()
            ghsaFiles.forEach { fileInfo ->
              val ghsaId = fileInfo["name"]!!.toString().removeSuffix(".json")
              val rawContent = githubFetchRaw(fileInfo["download_url"]!!.toString(), token)
              entries.addAll(GhsaEnrichmentParser.parse(ghsaId, rawContent))
            }

            outputFile.writeText(JsonOutput.toJson(mapOf("version" to 1, "entries" to entries)))
            logger.lifecycle(
                "sca_cves.json: ${entries.size} entries from ${ghsaFiles.size} GHSA files")
            logger.lifecycle(
                "Remember to commit src/main/resources/sca_cves.json after updating the database.")
          }
        }

    project.tasks.named("processResources") {
      dependsOn(generateTask)
      doLast {
        project
            .fileTree(mapOf("dir" to outputs.files.asPath, "includes" to listOf("**/*.json")))
            .forEach { f -> f.writeText(JsonOutput.toJson(JsonSlurper().parse(f))) }
      }
    }
  }

  private fun githubConnect(url: String, token: String?): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    if (!token.isNullOrEmpty()) {
      connection.setRequestProperty("Authorization", "Bearer $token")
    }
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    val code = connection.responseCode
    if (code != 200) {
      throw GradleException(
          "GitHub API returned HTTP $code for $url.\n" +
              "Unauthenticated rate limit is 60 req/hr. Set GITHUB_TOKEN to raise it.")
    }
    return connection
  }

  private fun githubFetch(url: String, token: String?): Any {
    val conn = githubConnect(url, token)
    return try {
      JsonSlurper().parse(conn.inputStream)
    } finally {
      conn.disconnect()
    }
  }

  private fun githubFetchRaw(url: String, token: String?): String {
    val conn = githubConnect(url, token)
    return try {
      conn.inputStream.bufferedReader().readText()
    } finally {
      conn.disconnect()
    }
  }
}
