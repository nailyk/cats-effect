/*
 * Copyright 2020-2025 Typelevel
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

package cats.effect

/**
 * Represents a snapshot of all live fibers in the runtime.
 *
 * @note
 *   the snapshot introduces a risk of memory leaks because it retains hard references to the
 *   underlying Fiber instances. As a result, these fibers cannot be garbage collected while the
 *   snapshot (or anything that retains it) is still in scope.
 */
sealed trait FiberSnapshot {

  /**
   * The list of all global (non-worker-local) fibers.
   */
  def global: List[FiberInfo]

  /**
   * Mapping of worker threads to their currently active fibers.
   */
  def workers: Map[WorkerInfo, List[FiberInfo]]
}

object FiberSnapshot {

  private val Empty: FiberSnapshot = FiberSnapshotImpl(Nil, Map.empty)

  def apply(global: List[FiberInfo], workers: Map[WorkerInfo, List[FiberInfo]]): FiberSnapshot =
    FiberSnapshotImpl(global, workers)

  def empty: FiberSnapshot = Empty

  private final case class FiberSnapshotImpl(
      global: List[FiberInfo],
      workers: Map[WorkerInfo, List[FiberInfo]]
  ) extends FiberSnapshot

}
