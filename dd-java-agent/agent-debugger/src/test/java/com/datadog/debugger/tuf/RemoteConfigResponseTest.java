package com.datadog.debugger.tuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static utils.TestHelper.getFixtureContent;

import com.squareup.moshi.Moshi;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class RemoteConfigResponseTest {
  private final Moshi moshi = new Moshi.Builder().build();
  private static final String LIVE_DEBUGGING_CONFIG_PATH =
      "datadog/2/LIVE_DEBUGGING/6bb44c5c-95f8-3828-81fd-b3c977b883d1/config";

  @Test
  public void success() throws IOException, URISyntaxException {
    byte[] body = getFixtureContent("/tuf/remote-config.json").getBytes(StandardCharsets.UTF_8);

    RemoteConfigResponse remoteConfigResponse =
        new RemoteConfigResponse(new ByteArrayInputStream(body), moshi);
    Map<String, Object> targetsJson = remoteConfigResponse.getTargetsJson();
    assertTrue(targetsJson.get("signatures") instanceof List);

    //    TargetsJson targetsJsonMeta = new TargetsJson(targetsJson);
    Optional<byte[]> maybeFileContents =
        remoteConfigResponse.getFileContents(LIVE_DEBUGGING_CONFIG_PATH);
    assertTrue(maybeFileContents.isPresent());
    assertEquals(maybeFileContents.get().length, 1629);

    assertFalse(remoteConfigResponse.getFileContents("missing-file").isPresent());
  }

  @Test
  public void parseFailure() {
    byte[] malformedJson = "invalid-json".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        IntegrityCheckException.class,
        () -> new RemoteConfigResponse(new ByteArrayInputStream(malformedJson), moshi));

    byte[] invalidJson = "{}".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        IntegrityCheckException.class,
        () -> new RemoteConfigResponse(new ByteArrayInputStream(invalidJson), moshi));
  }
}
