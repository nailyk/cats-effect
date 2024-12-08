package cats.effect.unsafe

// a native-specific mirror of WorkStealingThreadPoolConstants.java
private object WorkStealingThreadPoolConstants {

  /**
   * The number of unparked threads is encoded as an unsigned 16 bit number in the 16 most
   * significant bits of a 32 bit integer.
   */
  val UnparkShift: Int = 16

  /** Constant used when parking a thread which was not searching for work. */
  val DeltaNotSearching: Int = 1 << UnparkShift

  /**
   * Constant used when parking a thread which was previously searching for work and also when
   * unparking any worker thread.
   */
  val DeltaSearching: Int = DeltaNotSearching | 1

  /**
   * The number of threads currently searching for work is encoded as an unsigned 16 bit number in
   * the 16 least significant bits of a 32 bit integer. Used for extracting the number of searching
   * threads.
   */
  val SearchMask: Int = (1 << UnparkShift) - 1

  /** Used for extracting the number of unparked threads. */
  val UnparkMask: Int = ~SearchMask

  /** Used for checking for new fibers from the external queue every few iterations. */
  val ExternalQueueTicks: Int = 64

  val ExternalQueueTicksMask: Int = ExternalQueueTicks - 1
}
