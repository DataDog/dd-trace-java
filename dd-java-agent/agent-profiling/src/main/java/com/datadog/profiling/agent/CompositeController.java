package com.datadog.profiling.agent;

import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.ControllerContext;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingInputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

public class CompositeController implements Controller {

  private final List<Controller> controllers;

  public CompositeController(List<Controller> controllers) {
    assert !controllers.isEmpty() : "no controllers created";
    this.controllers = controllers;
  }

  // visible for testing
  public List<Controller> getControllers() {
    return Collections.unmodifiableList(controllers);
  }

  @Nonnull
  @Override
  public OngoingRecording createRecording(
      @Nonnull String recordingName, ControllerContext.Snapshot context)
      throws UnsupportedEnvironmentException {
    List<OngoingRecording> recordings = new ArrayList<>(controllers.size());
    for (Controller controller : controllers) {
      recordings.add(controller.createRecording(recordingName, context));
    }
    return new CompositeOngoingRecording(recordings);
  }

  private static class CompositeOngoingRecording implements OngoingRecording {

    private final List<OngoingRecording> recordings;

    private CompositeOngoingRecording(List<OngoingRecording> recordings) {
      this.recordings = recordings;
    }

    @Nonnull
    @Override
    public RecordingData stop() {
      return compose(OngoingRecording::stop);
    }

    @Nonnull
    @Override
    public RecordingData snapshot(@Nonnull Instant start, ProfilingSnapshot.Kind kind) {
      return compose(recording -> recording.snapshot(start, kind));
    }

    @Override
    public void close() {
      recordings.forEach(OngoingRecording::close);
    }

    private RecordingData compose(Function<OngoingRecording, RecordingData> recorder) {
      return new CompositeRecordingData(
          recordings.stream().map(recorder).collect(Collectors.toList()));
    }
  }

  private static class CompositeRecordingData extends RecordingData {
    private final List<RecordingData> data;

    public CompositeRecordingData(List<RecordingData> data) {
      super(first(data).getStart(), first(data).getEnd(), first(data).getKind());
      this.data = data;
    }

    @Nonnull
    @Override
    public RecordingInputStream getStream() throws IOException {
      List<RecordingInputStream> streams = new ArrayList<>(data.size());
      for (RecordingData item : data) {
        streams.add(item.getStream());
      }
      return new RecordingInputStream(new SequenceInputStream(Collections.enumeration(streams)));
    }

    @Override
    public void release() {
      for (RecordingData data : data) {
        data.release();
      }
    }

    @Nonnull
    @Override
    public String getName() {
      return first(data).getName();
    }

    private static <T> T first(List<T> list) {
      return list.get(0);
    }
  }
}
