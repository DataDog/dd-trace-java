package datadog.trace.civisibility.git.tree;

import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.civisibility.communication.BackendApi;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Okio;

public class GitDataApi {

  private static final MediaType JSON = MediaType.get("application/json");
  private static final String SEARCH_COMMITS_URI = "git/repository/search_commits";

  private final BackendApi backendApi;
  private final Moshi moshi;

  public GitDataApi(BackendApi backendApi) {
    this.backendApi = backendApi;
    moshi = new Moshi.Builder().build();
  }

  public List<String> searchCommits(String gitRemoteUrl, List<String> commitShas)
      throws IOException {
    List<Commit> commits = commitShas.stream().map(Commit::new).collect(Collectors.toList());
    SearchCommitsRequest searchCommitsRequest =
        new SearchCommitsRequest(commits, new Meta(gitRemoteUrl));

    JsonAdapter<SearchCommitsRequest> searchCommitsRequestAdapter =
        moshi.adapter(SearchCommitsRequest.class);
    JsonAdapter<SearchCommitsResponse> searchCommitsResponseAdapter =
        moshi.adapter(SearchCommitsResponse.class);

    String json = searchCommitsRequestAdapter.toJson(searchCommitsRequest);
    RequestBody requestBody = RequestBody.create(JSON, json);
    SearchCommitsResponse response =
        backendApi.post(
            SEARCH_COMMITS_URI,
            requestBody,
            is -> searchCommitsResponseAdapter.fromJson(Okio.buffer(Okio.source(is))));

    return response.data.stream().map(Commit::getId).collect(Collectors.toList());
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
