package datadog.trace.core.jfr.openjdk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import jdk.jfr.consumer.RecordingFile;
import jdk.jfr.Recording;

import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.ControllerFactory;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingInputStream;
import datadog.trace.api.Config;

public class JfrHelper {

  @SuppressWarnings("deprecation") // Config in datadog.trace.api has been deprecated
  public static Object startRecording() throws Exception {
    Controller controller = ControllerFactory.createController(Config.get());
    return controller.createRecording("recording");
  }

  public static List<?> stopRecording(final Object object) throws IOException {
    final RecordingInputStream stream = ((OngoingRecording) object).stop().getStream();
    final File output = Files.createTempFile("recording", ".jfr").toFile();
    output.deleteOnExit();
    stream.transferTo(new FileOutputStream(output));

    return RecordingFile.readAllEvents(output.toPath());
  }
}
