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

package cats
package effect
package std

import cats.effect.kernel._
import cats.syntax.all._

/**
 * This is a total map from `K` to `AtomicCell[F, V]`.
 *
 * It is conceptually similar to a `AtomicMap[F, Map[K, V]]`, but with better ergonomics when
 * working on a per key basis. Note, however, that it does not support atomic updates to
 * multiple keys.
 *
 * Additionally, it also provide less contention: since all operations are performed on
 * individual key-value pairs, the pairs can be sharded by key. Thus, multiple concurrent
 * updates may be executed independently to each other, as long as their keys belong to
 * different shards.
 */
trait AtomicMap[F[_], K, V] extends Function1[K, AtomicCell[F, V]] {

  /**
   * Access the [[cats.effect.std.AtomicCell]] for the given `key`.
   */
  def apply(key: K): AtomicCell[F, V]
}

object AtomicMap {

  /**
   * Creates a new `AtomicMap`.
   */
  def apply[F[_], K, V](implicit F: Concurrent[F]): F[AtomicMap[F, K, Option[V]]] =
    (KeyedMutex[F, K], MapRef[F, K, V]).mapN { (keyedMutex, valuesMapRef) =>
      new ConcurrentImpl(keyedMutex, valuesMapRef)
    }

  private[effect] final class ConcurrentImpl[F[_], K, V](
      keyedMutex: KeyedMutex[F, K],
      valuesMapRef: MapRef[F, K, Option[V]]
  )(
      implicit F: Concurrent[F]
  ) extends AtomicMap[F, K, Option[V]] {
    override def apply(key: K): AtomicCell[F, Option[V]] =
      new AtomicCell.ConcurrentImpl(
        ref = valuesMapRef(key),
        lock = keyedMutex.lock(key)
      )
  }
}
