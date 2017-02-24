package is.hail.utils

import breeze.linalg.DenseVector
import is.hail.stats.{LinRegBuilder, SparseGtBuilder, SparseGtVectorAndStats}

import scala.collection.mutable

abstract class IntIterator {
  self =>

  def nextInt(): Int

  def hasNext: Boolean

  // requires that hasNext is called exactly once between each call to nextInt
  // requires that `this` is at least as long as `that` (excess `this` is filtered)
  def unsafeFilter(that: Iterator[Boolean]): IntIterator = new IntIterator {
    def nextInt(): Int = self.nextInt()

    def hasNext: Boolean = {
      while (that.hasNext && self.hasNext) {
        if (that.next())
          return true
        else
          self.nextInt()
      }
      false
    }
  }

  def toArray: Array[Int] = {
    val b = new mutable.ArrayBuilder.ofInt
    while (hasNext)
      b += nextInt()
    b.result()
  }

  def foreach(f: Int => Unit) {
    while (hasNext)
      f(nextInt())
  }

  def countNonNegative(): Int = {
    var count = 0
    while (hasNext) {
      if (nextInt() >= 0) count += 1
    }
    count
  }
}
