/*
 * Copyright 2019 Datadog
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package datadog.trace.api.profiling;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Platform-agnostic API for operations required when retrieving data using the ProfilingSystem. */
public abstract class RecordingData implements ProfilingSnapshot {
  protected final Instant start;
  protected final Instant end;
  protected final Kind kind;

  public RecordingData(final Instant start, final Instant end, Kind kind) {
    this.start = start;
    this.end = end;
    this.kind = kind;
  }

  /**
   * @return the data stream if it contains any data.
   * @throws IOException if the stream to return is empty or another IO-related problem occurred.
   */
  @Nonnull
  public abstract RecordingInputStream getStream() throws IOException;

  /**
   * Releases the resources associated with the recording, for example the underlying file.
   *
   * <p>Forgetting to releasing this when done streaming, will lead to one or more of the following:
   *
   * <ul>
   *   <li>Memory leak
   *   <li>File leak
   * </ul>
   *
   * <p>Please don't forget to call release when done streaming...
   */
  public abstract void release();

  /**
   * Returns the name of the recording from which the data is originating.
   *
   * @return the name of the recording from which the data is originating.
   */
  @Nonnull
  public abstract String getName();

  /**
   * Returns the requested start time for the recording.
   *
   * <p>Note that this doesn't necessarily have to match the time for the actual data recorded.
   *
   * @return the requested start time.
   */
  @Nonnull
  public final Instant getStart() {
    return start;
  }

  /**
   * Returns the requested end time for the recording.
   *
   * <p>Note that this doesn't necessarily have to match the time for the actual data recorded.
   *
   * @return the requested end time.
   */
  @Nonnull
  public final Instant getEnd() {
    return end;
  }

  @Nonnull
  public final Kind getKind() {
    return kind;
  }

  /**
   * Returns the file path backing this recording data, if available. Implementations that store
   * recording data on disk can override this to avoid unnecessary stream materialization.
   *
   * @return the file path, or {@code null} if the data is not file-backed
   */
  @Nullable
  public Path getPath() {
    return null;
  }

  @Override
  public final String toString() {
    return "name=" + getName() + ", kind=" + getKind();
  }
}
