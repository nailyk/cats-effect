package cats.effect
package unsafe

import java.time.Instant
import java.time.temporal.ChronoField

trait WorkStealingThreadPoolPlatform[P <: AnyRef] extends Scheduler { this: WorkStealingThreadPool[P] =>

  override def nowMicros(): Long = {
    val now = Instant.now()
    now.getEpochSecond() * 1000000 + now.getLong(ChronoField.MICRO_OF_SECOND)
  }
}
