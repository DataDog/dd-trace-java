package datadog.trace.logging.ddlogger;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Marker;

public class TelemetryMarker implements Marker {
  private static final long serialVersionUID = -2849567615646933777L;
  private final String name;
  private List<Marker> referenceList = new CopyOnWriteArrayList();
  private static String OPEN = "[ ";
  private static String CLOSE = " ]";
  private static String SEP = ", ";

  TelemetryMarker(String name) {
    if (name == null) {
      throw new IllegalArgumentException("A marker name cannot be null");
    } else {
      this.name = name;
    }
  }

  public String getName() {
    return this.name;
  }

  public void add(Marker reference) {
    if (reference == null) {
      throw new IllegalArgumentException("A null value cannot be added to a Marker as reference.");
    } else if (!this.contains(reference)) {
      if (!reference.contains(this)) {
        this.referenceList.add(reference);
      }
    }
  }

  public boolean hasReferences() {
    return this.referenceList.size() > 0;
  }

  public boolean hasChildren() {
    return this.hasReferences();
  }

  public Iterator<Marker> iterator() {
    return this.referenceList.iterator();
  }

  public boolean remove(Marker referenceToRemove) {
    return this.referenceList.remove(referenceToRemove);
  }

  public boolean contains(Marker other) {
    if (other == null) {
      throw new IllegalArgumentException("Other cannot be null");
    } else if (this.equals(other)) {
      return true;
    } else {
      if (this.hasReferences()) {
        Iterator var2 = this.referenceList.iterator();

        while (var2.hasNext()) {
          Marker ref = (Marker) var2.next();
          if (ref.contains(other)) {
            return true;
          }
        }
      }

      return false;
    }
  }

  public boolean contains(String name) {
    if (name == null) {
      throw new IllegalArgumentException("Other cannot be null");
    } else if (this.name.equals(name)) {
      return true;
    } else {
      if (this.hasReferences()) {
        Iterator var2 = this.referenceList.iterator();

        while (var2.hasNext()) {
          Marker ref = (Marker) var2.next();
          if (ref.contains(name)) {
            return true;
          }
        }
      }

      return false;
    }
  }

  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    } else if (obj == null) {
      return false;
    } else if (!(obj instanceof Marker)) {
      return false;
    } else {
      Marker other = (Marker) obj;
      return this.name.equals(other.getName());
    }
  }

  public int hashCode() {
    return this.name.hashCode();
  }

  public String toString() {
    if (!this.hasReferences()) {
      return this.getName();
    } else {
      Iterator<Marker> it = this.iterator();
      StringBuilder sb = new StringBuilder(this.getName());
      sb.append(' ').append(OPEN);

      while (it.hasNext()) {
        Marker reference = (Marker) it.next();
        sb.append(reference.getName());
        if (it.hasNext()) {
          sb.append(SEP);
        }
      }

      sb.append(CLOSE);
      return sb.toString();
    }
  }
}
