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

// a native-specific mirror of WorkStealingThreadPoolConstants.java
private object WorkStealingThreadPoolConstants {

  /**
   * The number of unparked threads is encoded as an unsigned 16 bit number in the 16 most
   * significant bits of a 32 bit integer.
   */
  val UnparkShift: Int = 16

  /**
   * Constant used when parking a thread which was not searching for work.
   */
  val DeltaNotSearching: Int = 1 << UnparkShift

  /**
   * Constant used when parking a thread which was previously searching for work and also when
   * unparking any worker thread.
   */
  val DeltaSearching: Int = DeltaNotSearching | 1

  /**
   * The number of threads currently searching for work is encoded as an unsigned 16 bit number
   * in the 16 least significant bits of a 32 bit integer. Used for extracting the number of
   * searching threads.
   */
  val SearchMask: Int = (1 << UnparkShift) - 1

  /**
   * Used for extracting the number of unparked threads.
   */
  val UnparkMask: Int = ~SearchMask

  /**
   * Used for checking sources of external work every few iterations.
   */
  val ExternalWorkTicks: Int = 32

  val ExternalWorkTicksMask: Int = ExternalWorkTicks - 1

  val PollingTicks: Int = 2 * ExternalWorkTicks

  val PollingTicksMask: Int = PollingTicks - 1

}
