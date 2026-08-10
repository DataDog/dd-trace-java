package datadog.trace.civisibility.config.api.dto;

import com.squareup.moshi.Json;
import javax.annotation.Nullable;

public final class PageInfo {

  private PageInfo() {}

  public static final class Request {
    @Json(name = "page_state")
    @Nullable
    public final String pageState;

    public Request(@Nullable String pageState) {
      this.pageState = pageState;
    }
  }

  public static final class Response {
    public final String cursor;
    public final int size;

    @Json(name = "has_next")
    public final boolean hasNext;

    public Response(String cursor, int size, boolean hasNext) {
      this.cursor = cursor;
      this.size = size;
      this.hasNext = hasNext;
    }
  }
}
