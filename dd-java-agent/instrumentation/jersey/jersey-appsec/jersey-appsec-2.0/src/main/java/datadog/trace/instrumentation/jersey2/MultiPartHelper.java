package datadog.trace.instrumentation.jersey2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.ws.rs.core.MediaType;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.message.internal.MediaTypes;

public final class MultiPartHelper {

  private MultiPartHelper() {}

  public static void collectBodyPart(
      FormDataBodyPart bodyPart, Map<String, List<String>> bodyMap, List<String> filenames) {
    if (bodyMap != null
        && MediaTypes.typeEqual(MediaType.TEXT_PLAIN_TYPE, bodyPart.getMediaType())) {
      // BodyPartEntity allows re-reading the part without consuming the stream
      bodyMap.computeIfAbsent(bodyPart.getName(), k -> new ArrayList<>()).add(bodyPart.getValue());
    }
    if (filenames != null) {
      String filename = filenameFromBodyPart(bodyPart);
      if (filename != null) {
        filenames.add(filename);
      }
    }
  }

  public static String filenameFromBodyPart(FormDataBodyPart bodyPart) {
    FormDataContentDisposition cd = bodyPart.getFormDataContentDisposition();
    if (cd == null) return null;
    String filename = cd.getFileName();
    return (filename == null || filename.isEmpty()) ? null : filename;
  }
}
