package com.datadog.appsec.powerwaf;

import sun.misc.Unsafe;

import java.util.concurrent.atomic.AtomicInteger;

public class AtomicRequestCounter {

    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long counterOffset;

    static {
        try {
            counterOffset = unsafe.objectFieldOffset
                    (AtomicInteger.class.getDeclaredField("counter"));
        } catch (Exception ex) { throw new Error(ex); }
    }

    private volatile long counter;
    private volatile long timestamp;

    public final long get() {
        return counter;
    }

    public final long getAndReset() {
        long value = counter;
        unsafe.getAndSetLong(this, counter, 0);
        timestamp = 0;
        return value;
    }

    public final void increment() {
        if (timestamp == 0) {
            timestamp = System.currentTimeMillis();
        }
        unsafe.getAndAddLong(this, counterOffset, 1);
    }

    public final long getTimestamp() {
        return timestamp;
    }
}
