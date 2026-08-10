import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes valid JSON that is not in API Gateway response format (no statusCode/headers/body). */
public class HandlerStreamingWithRawJson implements RequestStreamHandler {
  @Override
  public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context)
      throws IOException {
    outputStream.write("{\"result\": \"hello\"}".getBytes(StandardCharsets.UTF_8));
  }
}
