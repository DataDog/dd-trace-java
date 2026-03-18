package datadog.trace.instrumentation.websocket.org;

import java.util.ArrayList;
import java.util.List;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.extensions.IExtension;
import org.java_websocket.protocols.IProtocol;

public class TraceDraft_6455 extends  Draft_6455{

  public TraceDraft_6455() {
    super();
  }

  public TraceDraft_6455(List<IExtension> inputExtensions) {
    super(inputExtensions);
  }

  public TraceDraft_6455(List<IExtension> inputExtensions, List<IProtocol> inputProtocols) {
    super(inputExtensions, inputProtocols);
  }

  public TraceDraft_6455(List<IExtension> inputExtensions, List<IProtocol> inputProtocols, int maxFrameSize) {
    super(inputExtensions, inputProtocols, maxFrameSize);
  }

  public static TraceDraft_6455 fromDraft6455(Draft_6455 original) {
    List<IExtension> extensions = new ArrayList<>();
    for (IExtension ext : original.getKnownExtensions()) {
      extensions.add(ext);
    }

    List<IProtocol> protocols = new ArrayList<>();
    for (IProtocol proto : original.getKnownProtocols()) {
      protocols.add(proto);
    }

    return new TraceDraft_6455(
        extensions,
        protocols,
        original.getMaxFrameSize()
    );
  }

  public Draft copyInstance() {
    ArrayList<IExtension> newExtensions = new ArrayList();
    for(IExtension knownExtension : this.getKnownExtensions()) {
      newExtensions.add(knownExtension.copyInstance());
    }

    ArrayList<IProtocol> newProtocols = new ArrayList();

    for(IProtocol knownProtocol : this.getKnownProtocols()) {
      newProtocols.add(knownProtocol.copyInstance());
    }

    return new TraceDraft_6455(newExtensions, newProtocols,getMaxFrameSize());
  }

  @Override
  public boolean equals(Object o) {
    return this == o || super.equals(o);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }
}
