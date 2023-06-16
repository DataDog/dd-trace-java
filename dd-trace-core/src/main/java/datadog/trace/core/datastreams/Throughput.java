package datadog.trace.core.datastreams;

public class Throughput {
  private long fanIn;
  private long fanOut;
  private long terminated;
  private long consumed;
  private long produced;
  private long generated;

  public Throughput() {
    this.fanIn = 0;
    this.fanOut = 0;
    this.terminated = 0;
    this.consumed = 0;
    this.produced = 0;
    this.generated = 0;
  }

  public long getFanIn() {
    return fanIn;
  }
  public long getFanOut() {
    return fanOut;
  }
  public long getTerminated() {
    return terminated;
  }
  public long getProduced() {
    return produced;
  }
  public long getConsumed() {
    return consumed;
  }
  public long getGenerated() {
    return generated;
  }

  public void incrementFanIn() {
    this.fanIn++;
  }
  public void incrementFanOut() {
    this.fanOut++;
  }

  public void incrementTerminated() {
    this.terminated++;
  }

  public void incrementProduced() {
    this.produced++;
  }

  public void incrementConsumed() {
    this.consumed++;
  }

  public void incrementGenerated() {
    this.generated++;
  }

  @Override
  public String toString() {
    return "Throughput{"
        + "fanIn="
        + fanIn
        + ", fanOut="
        + fanOut
        + ", terminated="
        + terminated
        + ", consumed="
        + consumed
        + ", produced="
        + produced
        + ", generated="
        + generated
        + '}';
  }
}
