package com.datadog.profiling.auxiliary;

import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.utils.ProfilingMode;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Collections;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** An interface to be implemented by a particular auxiliary profiler implementation */
public interface AuxiliaryImplementation {
  /** A singleton representing a no-op auxiliary implementation */
  AuxiliaryImplementation NULL =
      new AuxiliaryImplementation() {
        @Override
        public boolean isAvailable() {
          return false;
        }

        @Override
        public OngoingRecording start() {
          return null;
        }

        @Override
        public RecordingData stop(OngoingRecording recording) {
          return null;
        }

        @Override
        public Set<ProfilingMode> enabledModes() {
          return Collections.emptySet();
        }
      };

  /**
   * An implementation provider.<br>
   * A provider can respond to a requested profiler type and provide the corresponding {@linkplain
   * AuxiliaryImplementation} instance.
   */
  interface Provider {
    /**
     * Check whether this provider can handle this particular profiler type
     *
     * @param expectedType the expected profiler type
     * @return {@literal true} when the provider can handle this profiler type
     */
    boolean canProvide(@Nonnull String expectedType);

    /**
     * Create a new {@linkplain AuxiliaryImplementation} instance using the provided {@linkplain
     * ConfigProvider}
     *
     * @param configProvider the configuration
     * @return a new {@linkplain AuxiliaryImplementation} instance
     */
    @Nonnull
    AuxiliaryImplementation provide(@Nonnull ConfigProvider configProvider);
  }

  /**
   * Checks whether this particular implementation is available.<br>
   * The availability depends on the environment and agent settings.
   *
   * @return {@literal true} if this implementation is available
   */
  boolean isAvailable();

  /** @return a set of {@link ProfilingMode profiling modes} enabled for this implementation */
  @Nonnull
  Set<ProfilingMode> enabledModes();

  /**
   * Start the profiling process and open a new {@linkplain OngoingRecording} instance
   *
   * @return the associated {@linkplain OngoingRecording} instance or {@literal null}
   */
  @Nullable
  OngoingRecording start();

  /**
   * Stop the profiling.
   *
   * @param recording the associated {@linkplain OngoingRecording} instance or {@literal null}
   */
  @Nullable
  RecordingData stop(OngoingRecording recording);
}
