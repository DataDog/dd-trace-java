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
package com.datadog.profiling.controller.oracle;

import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import javax.annotation.Nonnull;
import javax.management.ObjectName;

/** Implementation for profiling recordings. */
public class OracleJdkRecordingData extends RecordingData {
  private final ObjectName recordingId;
  private final String name;

  private final JfrMBeanHelper helper;

  OracleJdkRecordingData(
      @Nonnull String name,
      @Nonnull ObjectName recordingId,
      @Nonnull Instant start,
      @Nonnull Instant end,
      @Nonnull Kind kind,
      @Nonnull JfrMBeanHelper helper) {
    super(start, end, kind);
    this.name = name;
    this.recordingId = recordingId;
    this.helper = helper;
  }

  @Override
  @Nonnull
  public RecordingInputStream getStream() throws IOException {
    return new RecordingInputStream(new JfrRecordingStream());
  }

  @Override
  protected void doRelease() {
    // noop
  }

  @Override
  @Nonnull
  public String getName() {
    return name;
  }

  private class JfrRecordingStream extends InputStream {
    private byte[] buf = new byte[0];
    private int count = 0;
    private int pos = 0;
    private boolean closed = false;
    private boolean endOfStream = false;

    private long streamId = -1L;

    @Override
    public synchronized int read() throws IOException {
      ensureOpen();
      if (pos >= buf.length) {
        if (closed || endOfStream) {
          return -1;
        }
        fill();
        if (endOfStream) {
          return -1;
        }
      }
      return buf[pos++] & 0xff;
    }

    private void ensureOpen() throws IOException {
      if (closed) {
        throw new IOException("Stream closed"); // $NON-NLS-1$
      }
    }

    @Override
    public synchronized int available() throws IOException {
      ensureOpen();
      if (pos >= buf.length) {
        if (closed || endOfStream) {
          return -1;
        }
        fill();
        if (endOfStream) {
          return -1;
        }
      }
      return count - pos;
    }

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      try {
        if (streamId != -1) {
          helper.closeStream(streamId);
        }
        helper.closeRecording(recordingId);
      } catch (Exception e) {
        throw new IOException(e);
      }
    }

    private void fill() throws IOException {
      if (streamId == -1L) {
        streamId =
            helper.openStream(
                recordingId, new Date(start.toEpochMilli()), new Date(end.toEpochMilli()));
      }
      buf = helper.readStream(streamId);
      if (buf != null) {
        count += buf.length;
        pos = 0;
      } else {
        pos = 0;
        count = 0;
        buf = new byte[0];
        endOfStream = true;
      }
    }
  }
}
