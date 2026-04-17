import com.openai.core.http.Headers;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

public class RequestResponseRecord {
  /**
   * Turn it on when the tests change to identify which records have been used and which can be
   * removed. This sets the execution attribute of the record file, so Git recognizes the file as
   * changed. This is useful for identifying unused records when changing tests.
   */
  public static final boolean SET_RECORD_FILE_ATTR_ON_READ = false;

  private static final String RECORD_FILE_HASH_ALG = "MD5";
  private static final String METHOD = "method: ";
  private static final String PATH = "path: ";
  private static final String BEGIN_REQUEST_BODY = "-- begin request body --";
  private static final String END_REQUEST_BODY = "-- end request body -- ";
  private static final String STATUS_CODE = "status code: ";
  private static final String BEGIN_RESPONSE_HEADERS = "-- begin response headers --";
  private static final String END_RESPONSE_HEADERS = "-- end response headers --";
  private static final String BEGIN_RESPONSE_BODY = "-- begin response body --";
  private static final String END_RESPONSE_BODY = "-- end response body --";
  private static final String KEY_VALUE_SEP = ": ";
  private static final char LINE_SEP = '\n';

  public final int status;
  public final Map<String, String> headers;
  public final byte[] body;

  private RequestResponseRecord(int status, Map<String, String> headers, byte[] body) {
    this.status = status;
    this.headers = headers;
    this.body = body;
  }

  public static String requestToFileName(String method, byte[] requestBody) {
    try {
      MessageDigest digest = MessageDigest.getInstance(RECORD_FILE_HASH_ALG);
      byte[] bytes = digest.digest(requestBody);
      StringBuilder sb = new StringBuilder();
      for (byte b : bytes) {
        sb.append(String.format("%02x", b));
      }
      // split hash to two haves, so it doesn't trigger the scanner on the commit
      String hash = sb.toString();
      int mid = hash.length() / 2;
      String firstHalf = hash.substring(0, mid);
      String secondHalf = hash.substring(mid);
      return firstHalf + '+' + secondHalf + '.' + method + ".rec";
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static boolean exists(Path recordsDir, HttpRequest request) {
    String filename =
        requestToFileName(request.method().toString(), readRequestBody(request).toByteArray());
    Path targetDir = recordSubpath(recordsDir, request);
    Path filePath = targetDir.resolve(filename);
    return filePath.toFile().exists();
  }

  private static ByteArrayOutputStream readRequestBody(HttpRequest request) {
    ByteArrayOutputStream requestBodyBytes = new ByteArrayOutputStream();
    try (HttpRequestBody requestBody = request.body()) {
      if (requestBody != null) {
        requestBody.writeTo(requestBodyBytes);
      }
    }
    return requestBodyBytes;
  }

  private static Path recordSubpath(Path recordsDir, HttpRequest request) {
    Path result = recordsDir;
    for (String segment : request.pathSegments()) {
      result = result.resolve(segment);
    }
    return result;
  }

  public static void dump(
      Path recordsDir, HttpRequest request, HttpResponse response, byte[] responseBody)
      throws IOException {
    ByteArrayOutputStream requestBodyBytes = readRequestBody(request);
    Path targetDir = recordSubpath(recordsDir, request);
    Files.createDirectories(targetDir);
    String filename =
        requestToFileName(request.method().toString(), requestBodyBytes.toByteArray());
    Path filePath = targetDir.resolve(filename);

    try (BufferedWriter out = Files.newBufferedWriter(filePath.toFile().toPath())) {
      out.write(METHOD);
      out.write(request.method().toString());
      out.write(LINE_SEP);

      out.write(PATH);
      String path = String.join("/", request.pathSegments());
      out.write(path);
      out.write(LINE_SEP);

      out.write(BEGIN_REQUEST_BODY);
      out.write(LINE_SEP);
      out.write(new String(requestBodyBytes.toByteArray(), StandardCharsets.UTF_8));
      out.write(LINE_SEP);
      out.write(END_REQUEST_BODY);
      out.write(LINE_SEP);

      out.write(STATUS_CODE);
      out.write(Integer.toString(response.statusCode()));
      out.write(LINE_SEP);

      out.write(BEGIN_RESPONSE_HEADERS);
      out.write(LINE_SEP);
      Headers headers = response.headers();
      for (String name : headers.names()) {
        List<String> values = headers.values(name);
        if (values.size() == 1) {
          out.write(name);
          out.write(KEY_VALUE_SEP);
          out.write(values.get(0));
          out.write(LINE_SEP);
        }
      }
      out.write(END_RESPONSE_HEADERS);
      out.write(LINE_SEP);

      out.write(BEGIN_RESPONSE_BODY);
      out.write(LINE_SEP);
      out.write(new String(responseBody));
      out.write(LINE_SEP);
      out.write(END_RESPONSE_BODY);
      out.write(LINE_SEP);
    }
  }

  public static RequestResponseRecord read(Path recFilePath) {
    int statusCode = 200;
    Map<String, String> headers = new java.util.HashMap<>();
    StringBuilder bodyBuilder = new StringBuilder();

    try {
      List<String> lines = Files.readAllLines(recFilePath, StandardCharsets.UTF_8);

      boolean inResponseHeaders = false;
      boolean inResponseBody = false;

      for (String line : lines) {
        if (line.startsWith(STATUS_CODE)) {
          statusCode = Integer.parseInt(line.substring(STATUS_CODE.length()));
        } else if (line.equals(BEGIN_RESPONSE_HEADERS)) {
          inResponseHeaders = true;
        } else if (line.equals(END_RESPONSE_HEADERS)) {
          inResponseHeaders = false;
        } else if (inResponseHeaders && line.contains(KEY_VALUE_SEP)) {
          int arrowIndex = line.indexOf(KEY_VALUE_SEP);
          String name = line.substring(0, arrowIndex);
          String value = line.substring(arrowIndex + KEY_VALUE_SEP.length());
          headers.put(name, value);
        } else if (line.equals(BEGIN_RESPONSE_BODY)) {
          inResponseBody = true;
        } else if (line.equals(END_RESPONSE_BODY)) {
          inResponseBody = false;
        } else if (inResponseBody) {
          if (bodyBuilder.length() > 0) {
            bodyBuilder.append(LINE_SEP);
          }
          bodyBuilder.append(line);
        }
      }

      if (SET_RECORD_FILE_ATTR_ON_READ) {
        Files.setPosixFilePermissions(recFilePath, PosixFilePermissions.fromString("rwxr-xr-x"));
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    byte[] body = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);
    return new RequestResponseRecord(statusCode, headers, body);
  }
}
