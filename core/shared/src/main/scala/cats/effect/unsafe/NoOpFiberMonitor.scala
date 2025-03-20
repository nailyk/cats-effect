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
package unsafe

/**
 * A no-op implementation of an unordered bag used for tracking asynchronously suspended fiber
 * instances on Scala Native and on Scala.js when weak refs are unavailable. This is used as a
 * fallback.
 */
private final class NoOpFiberMonitor extends FiberMonitor(null) {
  private final val noop: WeakBag.Handle = () => ()
  override def monitorSuspended(fiber: IOFiber[?]): WeakBag.Handle = noop
  override def liveFiberSnapshot(print: String => Unit): Unit = {}
}
