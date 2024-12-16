package datadog.trace.payloadtags;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts redaction rules from a "service-2.json" file based on "sensitive" field definitions.
 *
 * <p>"service-2.json" files can be found in <a href="https://github.com/aws/aws-sdk-java-v2"/>.
 */
public class RedactionRulesExtractor {

  public static void main(String[] args) throws IOException {
    if (args.length == 0) {
      System.out.println("Usage: RedactionRulesExtractor <service-2.json>");
      return;
    }

    String service2json = args[0];

    File jsonFile = new File(service2json);

    if (!jsonFile.exists()) {
      System.out.println("File not found: " + service2json);
      return;
    }

    String json = new String(Files.readAllBytes(jsonFile.toPath()));

    JsonAdapter<Map<String, Object>> adapter =
        new Moshi.Builder()
            .build()
            .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

    Map<String, Object> map = adapter.fromJson(json);

    if (!"2.0".equals(map.get("version"))) {
      System.out.println("$.version != 2.0");
    }

    Map<String, Object> allShapes = (Map<String, Object>) map.get("shapes");
    Map<String, Object> operations = (Map<String, Object>) map.get("operations");

    Set<String> requestSensitivePaths = new LinkedHashSet<>();
    Set<String> responseSensitivePaths = new LinkedHashSet<>();
    Set<String> errorsSensitivePaths = new LinkedHashSet<>();

    // traverse operations and for each check its input/output allShapes
    for (Map.Entry<String, Object> operation : operations.entrySet()) {
      String operationName = operation.getKey();
      Map<String, Object> operationObject = (Map<String, Object>) operation.getValue();

      // input shape
      Map<String, Object> inputObject = (Map<String, Object>) operationObject.get("input");
      if (inputObject != null) {
        String inputShape = (String) inputObject.get("shape");
        collectSensitivePaths(
            operationName,
            (Map<String, Object>) allShapes.get(inputShape),
            allShapes,
            "$",
            requestSensitivePaths);
      }

      // output shape
      Map<String, Object> outputObject = (Map<String, Object>) operationObject.get("output");
      if (outputObject != null) {
        String outputShape = (String) outputObject.get("shape");
        collectSensitivePaths(
            operationName,
            (Map<String, Object>) allShapes.get(outputShape),
            allShapes,
            "$",
            responseSensitivePaths);
      }

      List<Map<String, Object>> errors =
          (List<Map<String, Object>>)
              operationObject.getOrDefault("errors", Collections.emptyList());
      for (Map<String, Object> error : errors) {
        String errorShape = (String) error.get("shape");
        collectSensitivePaths(
            operationName,
            (Map<String, Object>) allShapes.get(errorShape),
            allShapes,
            "$",
            errorsSensitivePaths);
      }
    }

    LinkedHashSet<String> commonSensitivePaths = new LinkedHashSet<>();
    commonSensitivePaths.addAll(requestSensitivePaths);
    commonSensitivePaths.retainAll(responseSensitivePaths);
    requestSensitivePaths.removeAll(commonSensitivePaths);
    responseSensitivePaths.removeAll(commonSensitivePaths);

    System.out.println("\nCommon sensitive paths:\n" + String.join("\n", commonSensitivePaths));
    System.out.println("\nRequest sensitive paths:\n" + String.join("\n", requestSensitivePaths));
    System.out.println("\nResponse sensitive paths:\n" + String.join("\n", responseSensitivePaths));
    System.out.println("\nErrors sensitive paths:\n" + String.join("\n", errorsSensitivePaths));
    Map<String, Object> metadata = (Map<String, Object>) map.get("metadata");
    System.out.println("serviceId: " + metadata.get("serviceId"));
    System.out.println("uid: " + metadata.get("uid"));
  }

  private static void collectSensitivePaths(
      String operationName,
      Map<String, Object> shape,
      Map<String, Object> allShapes,
      String path,
      Set<String> sensitivePathsOut) {
    if ((boolean) shape.getOrDefault("sensitive", false)) {
      sensitivePathsOut.add(path);
      return;
    }

    Object shapeType = shape.get("type");

    if ("structure".equals(shapeType)) {
      Map<String, Object> members =
          (Map<String, Object>) shape.getOrDefault("members", Collections.emptyMap());
      for (Map.Entry<String, Object> member : members.entrySet()) {
        String memberName = member.getKey();
        Map<String, Object> memberObject = (Map<String, Object>) member.getValue();
        String memberShape = (String) memberObject.get("shape");
        collectSensitivePaths(
            operationName,
            (Map<String, Object>) allShapes.get(memberShape),
            allShapes,
            path + "." + memberName,
            sensitivePathsOut);
      }
    } else if ("list".equals(shapeType)) {
      Map<String, Object> member = (Map<String, Object>) shape.get("member");
      String memberShape = (String) member.get("shape");
      collectSensitivePaths(
          operationName,
          (Map<String, Object>) allShapes.get(memberShape),
          allShapes,
          path + "[*]",
          sensitivePathsOut);
    }
  }
}
