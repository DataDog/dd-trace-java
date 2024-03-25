package com.datadog.profiling.utils;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public interface Timestamper {

  Timestamper DEFAULT = new Timestamper() {};

  default long timestamp() {
    return System.nanoTime();
  }

  default double toNanosConversionFactor() {
    return 1D;
  }

  final class Registration {
    volatile Timestamper pending = Timestamper.DEFAULT;
    private static final AtomicReferenceFieldUpdater<Registration, Timestamper> UPDATER =
        AtomicReferenceFieldUpdater.newUpdater(Registration.class, Timestamper.class, "pending");

    private static final Registration INSTANCE = new Registration();
  }

  /**
   * One shot chance to override the timestamper, which allows delayed initialisation of the
   * timestamp source.
   *
   * @return whether override was successful
   */
  static boolean override(Timestamper timestamper) {
    return Registration.UPDATER.compareAndSet(
        Registration.INSTANCE, Timestamper.DEFAULT, timestamper);
  }

  final class Singleton {
    //
    static final Timestamper TIMESTAMPER = Registration.INSTANCE.pending;
  }

  /**
   * Gets the registered timestamper (e.g. using JFR) if one has been registered, otherwise uses the
   * default timer.
   */
  static Timestamper timestamper() {
    return Singleton.TIMESTAMPER;
  }
}
