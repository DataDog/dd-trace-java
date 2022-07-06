package com.datadog.debugger.tuf;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles responses from Remote Configuration */
public class RemoteConfigResponse {
  private static final Logger log = LoggerFactory.getLogger(RemoteConfigResponse.class);

  private final Map<String, Object> targetsJson;
  private final List<Map<String, Object>> targetFiles;

  public RemoteConfigResponse(InputStream inputStream, Moshi moshi) {
    try {
      ParameterizedType type = Types.newParameterizedType(Map.class, String.class, Object.class);
      JsonAdapter<Map<String, Object>> adapter = moshi.adapter(type);
      Map<String, Object> map = adapter.fromJson(Okio.buffer(Okio.source(inputStream)));
      String targetsJsonBase64 = (String) map.get("targets");
      byte[] targetsJsonDecoded =
          Base64.getDecoder().decode(targetsJsonBase64.getBytes(StandardCharsets.UTF_8));
      this.targetsJson =
          (targetsJsonDecoded.length > 0)
              ? adapter.fromJson(new String(targetsJsonDecoded, StandardCharsets.UTF_8))
              : null;
      this.targetFiles = (List<Map<String, Object>>) map.get("target_files");
    } catch (Exception exception) {
      throw new IntegrityCheckException("Failed to parse fleet response", exception);
    }
  }

  public Map<String, Object> getTargetsJson() {
    return targetsJson;
  }

  public Optional<byte[]> getFileContents(String filePath) {
    if (targetFiles != null) {
      try {
        for (Map<String, Object> targetFile : targetFiles) {
          String path = (String) targetFile.get("path");
          if (path.equals(filePath)) {
            String raw = (String) targetFile.get("raw");
            return Optional.of(Base64.getDecoder().decode(raw));
          }
        }
      } catch (Exception exception) {
        throw new IntegrityCheckException(
            "Could not get file contents from fleet response", exception);
      }
    }
    return Optional.empty();
  }
}
