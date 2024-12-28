package cats.effect
package unsafe

import scala.scalanative.libc.errno._
import scala.scalanative.libc.string._
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.time._
import scala.scalanative.posix.timeOps._
import scala.scalanative.unsafe._

trait WorkStealingThreadPoolPlatform[P <: AnyRef] extends Scheduler { this: WorkStealingThreadPool[P] =>

  // TODO cargo culted from EventLoopExecutorScheduler.scala
  override def nowMicros(): Long =
    if (LinktimeInfo.isFreeBSD || LinktimeInfo.isLinux || LinktimeInfo.isMac) {
      val ts = stackalloc[timespec]()
      if (clock_gettime(CLOCK_REALTIME, ts) != 0)
        throw new RuntimeException(fromCString(strerror(errno)))
      ts.tv_sec * 1000000 + ts.tv_nsec / 1000
    } else {
      super.nowMicros()
    }
}
