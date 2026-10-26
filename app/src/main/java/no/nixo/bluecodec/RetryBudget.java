package no.nixo.bluecodec;

/** Each connection gets a finite window; codec broadcasts never reset it. */
final class RetryBudget {
  static final int[] DELAYS_MS = {1200, 2500, 5000, 9000};
  final long generation;
  private int attempts;
  volatile boolean canceled;

  RetryBudget(long generation) {
    this.generation = generation;
  }

  boolean available() {
    return attempts < DELAYS_MS.length;
  }

  int nextDelay() {
    if (!available()) throw new IllegalStateException("Retry budget exhausted");
    return DELAYS_MS[attempts++];
  }
}
