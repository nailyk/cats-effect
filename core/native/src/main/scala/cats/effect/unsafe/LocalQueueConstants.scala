/*
 * Copyright 2020-2024 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect.unsafe

// a native-specific mirror of LocalQueueConstants.java
private object LocalQueueConstants {

  /**
   * Fixed capacity of the [[cats.effect.unsafe.LocalQueue]] implementation, empirically determined
   * to provide a balance between memory footprint and enough headroom in the face of bursty
   * workloads which spawn a lot of fibers in a short period of time.
   *
   * <p>Must be a power of 2.
   */
  val LocalQueueCapacity: Int = 256

  /** Bitmask used for indexing into the circular buffer. */
  val LocalQueueCapacityMask: Int = LocalQueueCapacity - 1

  /** Half of the local queue capacity. */
  val HalfLocalQueueCapacity: Int = LocalQueueCapacity / 2

  /**
   * Spillover batch size. The runtime relies on the assumption that this number fully divides
   * `HalfLocalQueueCapacity`.
   */
  val SpilloverBatchSize: Int = 32

  /** Number of batches that fit into one half of the local queue. */
  val BatchesInHalfQueueCapacity: Int = HalfLocalQueueCapacity / SpilloverBatchSize

  /**
   * The maximum current capacity of the local queue which can still accept a full batch to be added
   * to the queue (remembering that one fiber from the batch is executed by directly and not
   * enqueued on the local queue).
   */
  val LocalQueueCapacityMinusBatch: Int = LocalQueueCapacity - SpilloverBatchSize + 1

  /** Bitmask used to extract the 16 least significant bits of a 32 bit integer value. */
  val UnsignedShortMask: Int = (1 << 16) - 1
}
