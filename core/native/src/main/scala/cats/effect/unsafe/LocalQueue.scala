package cats.effect.unsafe

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater

// native mirror of LocalQueue.java
private class Head {

  /**
   * The head of the queue.
   *
   * <p>Concurrently updated by many [[WorkerThread]] s.
   *
   * <p>Conceptually, it is a concatenation of two unsigned 16 bit values. Since the capacity of the
   * local queue is less than (2^16 - 1), the extra unused values are used to distinguish between
   * the case where the queue is empty (`head` == `tail`) and (`head` - `tail` ==
   * [[LocalQueueConstants.LocalQueueCapacity]]), which is an important distinction for other
   * [[WorkerThread]] s trying to steal work from the queue.
   *
   * <p>The least significant 16 bits of the integer value represent the ''real'' value of the head,
   * pointing to the next [[cats.effect.IOFiber]] instance to be dequeued from the queue.
   *
   * <p>The most significant 16 bits of the integer value represent the ''steal'' tag of the head.
   * This value is altered by another [[WorkerThread]] which has managed to win the race and become
   * the exclusive ''stealer'' of the queue. During the period in which the ''steal'' tag differs
   * from the ''real'' value, no other [[WorkerThread]] can steal from the queue, and the owner
   * [[WorkerThread]] also takes special care to not mangle the ''steal'' tag set by the
   * ''stealer''. The stealing [[WorkerThread]] is free to transfer half of the available
   * [[cats.effect.IOFiber]] object references from this queue into its own [[LocalQueue]] during
   * this period, making sure to undo the changes to the ''steal'' tag of the head on completion,
   * action which ultimately signals that stealing is finished.
   */
  @volatile
  private[this] var head: Int = 0
}

private object Head {
  private[unsafe] val updater: AtomicIntegerFieldUpdater[Head] =
      AtomicIntegerFieldUpdater.newUpdater(classOf[Head], "head")
}

private class Tail extends Head {

  /**
   * The tail of the queue.
   *
   * <p>Only ever updated by the owner [[WorkerThread]], but also read by other threads to determine
   * the current size of the queue, for work stealing purposes. Denotes the next available free slot
   * in the `buffer` array.
   *
   * <p>Conceptually, it is an unsigned 16 bit value (the most significant 16 bits of the integer
   * value are ignored in most operations).
   */
  protected var tail: Int = 0

  @volatile
  private[this] var tailPublisher: Int = 0
}

private object Tail {
  private[unsafe] val updater: AtomicIntegerFieldUpdater[Tail] =
      AtomicIntegerFieldUpdater.newUpdater(classOf[Tail], "tailPublisher");
}

private class LocalQueuePadding extends Tail
