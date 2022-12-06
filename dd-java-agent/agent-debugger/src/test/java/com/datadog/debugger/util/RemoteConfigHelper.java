package com.datadog.debugger.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public class RemoteConfigHelper {
  public static String encode(String s, String serviceName) {
    String encodedConfig = new String(Base64.getEncoder().encode(s.getBytes()));
    String path = UUID.nameUUIDFromBytes(serviceName.getBytes(StandardCharsets.UTF_8)).toString();
    return String.format(
        "{\n"
            + "\"targets\": \"\",\n"
            + "\"target_files\": [\n"
            + "    {\n"
            + "      \"path\": \"datadog/2/LIVE_DEBUGGING/%s/config\",\n"
            + "      \"raw\": \"%s\"\n"
            + "}]}",
        path, encodedConfig);
  }
}
