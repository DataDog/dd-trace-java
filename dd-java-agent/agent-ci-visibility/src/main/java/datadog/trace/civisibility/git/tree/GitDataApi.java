package datadog.trace.civisibility.git.tree;

import static datadog.http.client.HttpRequest.APPLICATION_JSON;

import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.communication.BackendApi;
import datadog.communication.util.IOUtils;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpRequestListener;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.civisibility.communication.TelemetryListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** API for making Git-data-related requests to backend */
public class GitDataApi {

  private static final Logger LOGGER = LoggerFactory.getLogger(GitDataApi.class);

  private static final String SEARCH_COMMITS_URI = "git/repository/search_commits";
  private static final String UPLOAD_PACKFILES_URI = "git/repository/packfile";

  private final BackendApi backendApi;
  private final CiVisibilityMetricCollector metricCollector;
  private final JsonAdapter<SearchCommitsRequest> searchCommitsRequestAdapter;
  private final JsonAdapter<SearchCommitsResponse> searchCommitsResponseAdapter;
  private final JsonAdapter<PushedSha> pushedShaAdapter;

  public GitDataApi(BackendApi backendApi, CiVisibilityMetricCollector metricCollector) {
    this.backendApi = backendApi;
    this.metricCollector = metricCollector;

    Moshi moshi = new Moshi.Builder().build();
    searchCommitsRequestAdapter = moshi.adapter(SearchCommitsRequest.class);
    searchCommitsResponseAdapter = moshi.adapter(SearchCommitsResponse.class);
    pushedShaAdapter = moshi.adapter(PushedSha.class);
  }

  /**
   * Searches the list of commits that exist in Git DB on the server side
   *
   * @param gitRemoteUrl Git remote URL
   * @param commitHashes List of commit SHAs that should be checked
   * @return SHAs of commits that already exist on the server
   * @throws IOException if remote request fails
   */
  public Collection<String> searchCommits(String gitRemoteUrl, List<String> commitHashes)
      throws IOException {
    List<Commit> commits = commitHashes.stream().map(Commit::new).collect(Collectors.toList());
    SearchCommitsRequest searchCommitsRequest =
        new SearchCommitsRequest(commits, new Meta(gitRemoteUrl));

    String json = searchCommitsRequestAdapter.toJson(searchCommitsRequest);
    HttpRequestBody requestBody = HttpRequestBody.of(json);

    HttpRequestListener telemetryListener =
        new TelemetryListener.Builder(metricCollector)
            .requestCount(CiVisibilityCountMetric.GIT_REQUESTS_SEARCH_COMMITS)
            .requestErrors(CiVisibilityCountMetric.GIT_REQUESTS_SEARCH_COMMITS_ERRORS)
            .requestDuration(CiVisibilityDistributionMetric.GIT_REQUESTS_SEARCH_COMMITS_MS)
            .build();

    SearchCommitsResponse response =
        backendApi.post(
            SEARCH_COMMITS_URI,
            APPLICATION_JSON,
            requestBody,
            is -> searchCommitsResponseAdapter.fromJson(Okio.buffer(Okio.source(is))),
            telemetryListener,
            false);

    return response.data.stream().map(Commit::getId).collect(Collectors.toSet());
  }

  /**
   * Uploads a Git pack file to Git DB on the server side
   *
   * @param gitRemoteUrl Git remote URL
   * @param currentCommitHash SHA of commit with which the pack file is associated
   * @param packFile Path to the fila that is being uploaded
   * @throws IOException if remote request fails
   */
  public void uploadPackFile(String gitRemoteUrl, String currentCommitHash, Path packFile)
      throws IOException {
    PushedSha pushedSha = new PushedSha(new Commit(currentCommitHash), new Meta(gitRemoteUrl));
    String pushedShaJson = pushedShaAdapter.toJson(pushedSha);
    HttpRequestBody pushedShaBody = HttpRequestBody.of(pushedShaJson);

    byte[] packFileContents = Files.readAllBytes(packFile);
    HttpRequestBody packFileBody = HttpRequestBody.of(packFileContents);

    String packFileName = packFile.getFileName().toString();
    String packFileNameWithoutRandomPrefix = packFileName.substring(packFileName.indexOf('-') + 1);

    HttpRequestBody.MultipartBuilder multipartBuilder = HttpRequestBody.multipart();
    multipartBuilder.addFormDataPart("pushedSha", "pushedSha.json", pushedShaBody);
    multipartBuilder.addFormDataPart("packfile", packFileNameWithoutRandomPrefix, packFileBody);
    String contentType = multipartBuilder.contentType();
    HttpRequestBody requestBody = multipartBuilder.build();

    HttpRequestListener telemetryListener =
        new TelemetryListener.Builder(metricCollector)
            .requestCount(CiVisibilityCountMetric.GIT_REQUESTS_OBJECTS_PACK)
            .requestErrors(CiVisibilityCountMetric.GIT_REQUESTS_OBJECTS_PACK_ERRORS)
            .requestDuration(CiVisibilityDistributionMetric.GIT_REQUESTS_OBJECTS_PACK_MS)
            .requestBytes(CiVisibilityDistributionMetric.GIT_REQUESTS_OBJECTS_PACK_BYTES)
            .build();

    String response =
        backendApi.post(
            UPLOAD_PACKFILES_URI,
            contentType,
            requestBody,
            IOUtils::readFully,
            telemetryListener,
            false);
    LOGGER.debug("Uploading pack file {} returned response {}", packFile, response);
  }

  private static final class SearchCommitsRequest {
    final List<Commit> data;
    final Meta meta;

    private SearchCommitsRequest(List<Commit> data, Meta meta) {
      this.data = data;
      this.meta = meta;
    }
  }

  private static final class SearchCommitsResponse {
    final List<Commit> data;

    private SearchCommitsResponse(List<Commit> data) {
      this.data = data;
    }
  }

  private static final class PushedSha {
    final Commit data;
    final Meta meta;

    private PushedSha(Commit data, Meta meta) {
      this.data = data;
      this.meta = meta;
    }
  }

  private static final class Meta {
    @Json(name = "repository_url")
    final String repositoryUrl;

    private Meta(String repositoryUrl) {
      this.repositoryUrl = repositoryUrl;
    }
  }

  private static final class Commit {
    final String id;
    final String type = "commit";

    Commit(String id) {
      this.id = id;
    }

    public String getId() {
      return id;
    }
  }
}
