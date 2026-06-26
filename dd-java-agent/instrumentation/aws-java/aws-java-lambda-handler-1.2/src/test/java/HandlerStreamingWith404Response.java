import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public class HandlerStreamingWith404Response implements RequestStreamHandler {
  @Override
  public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context)
      throws IOException {
    PrintWriter writer =
        new PrintWriter(
            new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)));
    writer.write(
        "{\"statusCode\": 404, "
            + "\"headers\": {\"content-type\": \"text/html\"}, "
            + "\"body\": \"Not Found\"}");
    writer.close();
  }
}
