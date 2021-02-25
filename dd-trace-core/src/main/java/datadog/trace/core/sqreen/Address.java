package datadog.trace.core.sqreen;

import java.util.Objects;

/**
 * @param <T> the type of data associated with the address
 */
public final class Address<T> implements Comparable<Address<?>> {
    private final String key;

    public Address(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Address<?> address = (Address<?>) o;
        return Objects.equals(key, address.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }

    @Override
    public int compareTo(Address<?> o) {
        return this.key.compareTo(o.key);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Address{");
        sb.append("key='").append(key).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
