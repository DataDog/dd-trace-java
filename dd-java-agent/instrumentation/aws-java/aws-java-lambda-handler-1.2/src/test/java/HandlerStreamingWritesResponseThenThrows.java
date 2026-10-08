import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class HandlerStreamingWritesResponseThenThrows implements RequestStreamHandler {
  @Override
  public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context)
      throws IOException {
    outputStream.write(
        ("{\"statusCode\":200,\"headers\":{\"content-type\":\"application/json\"},"
                + "\"body\":\"{\\\"discarded\\\":true}\"}")
            .getBytes(StandardCharsets.UTF_8));
    throw new Error("Some error");
  }
}
