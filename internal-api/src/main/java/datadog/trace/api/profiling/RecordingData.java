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
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Platform-agnostic API for operations required when retrieving data using the ProfilingSystem. */
public abstract class RecordingData implements ProfilingSnapshot {
  protected final Instant start;
  protected final Instant end;
  protected final Kind kind;

  // Reference counting for multiple listeners
  private final AtomicInteger refCount = new AtomicInteger(0); // Start at 0
  private volatile boolean released = false;

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
   * Increment reference count. Must be called once for each handler that will process this
   * RecordingData.
   *
   * <p>The reference count starts at 0, so every handler must call {@code retain()} before
   * processing and {@code release()} when done. When the last handler calls {@code release()}, the
   * reference count reaches 0 and resources are cleaned up.
   *
   * @return this instance for chaining
   * @throws IllegalStateException if the recording has already been released
   */
  @Nonnull
  public final synchronized RecordingData retain() {
    if (released) {
      throw new IllegalStateException("Cannot retain released RecordingData");
    }
    refCount.incrementAndGet();
    return this;
  }

  /**
   * Releases the resources associated with the recording, for example the underlying file.
   *
   * <p>This method uses reference counting to support multiple handlers. Each call to {@link
   * #retain()} must be matched with a call to {@code release()}. The actual resource cleanup via
   * {@link #doRelease()} happens when the reference count reaches zero.
   *
   * <p>Forgetting to release this when done streaming will lead to one or more of the following:
   *
   * <ul>
   *   <li>Memory leak
   *   <li>File leak
   * </ul>
   *
   * <p>Please don't forget to call release when done streaming...
   */
  public final void release() {
    boolean shouldRelease = false;
    synchronized (this) {
      if (released) {
        return;
      }
      int remaining = refCount.decrementAndGet();
      if (remaining == 0) {
        released = true;
        shouldRelease = true;
      } else if (remaining < 0) {
        throw new IllegalStateException("RecordingData over-released");
      }
    }
    if (shouldRelease) {
      doRelease();
    }
  }

  /**
   * Actual resource cleanup implementation. Subclasses must override this method instead of {@link
   * #release()}.
   *
   * <p>This method is called exactly once when the reference count reaches zero.
   */
  protected abstract void doRelease();

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
