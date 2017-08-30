package is.hail.utils

import is.hail.check.Gen
import is.hail.check.Prop._
import is.hail.check.Arbitrary._
import scala.collection.mutable
import org.scalatest._
import Matchers._
import org.testng.annotations.Test

class BinaryHeapSuite {
  @Test
  def insertOneIsMax() {
    val bh = new BinaryHeap[Int]()
    bh.insert(1, 10)
    assert(bh.max() === 1)
    assert(bh.max() === 1)
    assert(bh.size === 1)
    assert(bh.contains(1) === true)
    assert(bh.extractMax() === 1)
    assert(bh.contains(1) === false)
    assert(bh.size === 0)

    intercept[Exception](bh.max())
    intercept[Exception](bh.extractMax())
  }

  @Test
  def twoElements() {
    val bh = new BinaryHeap[Int]()
    bh.insert(1, 5)
    assert(bh.contains(1) == true)
    bh.insert(2, 10)
    assert(bh.contains(2) == true)
    assert(bh.max() === 2)
    assert(bh.max() === 2)
    assert(bh.size === 2)
    assert(bh.extractMax() === 2)
    assert(bh.contains(2) == false)
    assert(bh.size === 1)
    assert(bh.max() === 1)
    assert(bh.max() === 1)
    assert(bh.size === 1)
    assert(bh.extractMax() === 1)
    assert(bh.contains(1) == false)
    assert(bh.size === 0)
  }

  @Test
  def threeElements() {
    val bh = new BinaryHeap[Int]()
    bh.insert(1, -10)
    assert(bh.contains(1) == true)
    bh.insert(2, -5)
    assert(bh.contains(2) == true)
    bh.insert(3, -7)
    assert(bh.contains(3) == true)
    assert(bh.max() === 2)
    assert(bh.max() === 2)
    assert(bh.size === 3)
    assert(bh.extractMax() === 2)
    assert(bh.size === 2)

    assert(bh.max() === 3)
    assert(bh.max() === 3)
    assert(bh.size === 2)
    assert(bh.extractMax() === 3)
    assert(bh.size === 1)

    assert(bh.max() === 1)
    assert(bh.max() === 1)
    assert(bh.size === 1)
    assert(bh.extractMax() === 1)
    assert(bh.size === 0)

    assert(bh.contains(1) == false)
    assert(bh.contains(2) == false)
    assert(bh.contains(3) == false)
  }

  @Test
  def decreaseKey1() {
    val bh = new BinaryHeap[Int]()

    bh.insert(1, 0)
    bh.insert(2, 100)
    bh.insert(3, 10)

    bh.decreasePriorityTo(2, -10)
    assert(bh.max() === 3)
    assert(bh.extractMax() === 3)
    assert(bh.extractMax() === 1)
    assert(bh.extractMax() === 2)
  }

  @Test
  def decreaseKey2() {
    val bh = new BinaryHeap[Int]()

    bh.insert(1, 0)
    bh.insert(2, 100)
    bh.insert(3, 10)

    bh.decreasePriorityTo(1, -10)
    assert(bh.max() === 2)
    assert(bh.extractMax() === 2)
    assert(bh.extractMax() === 3)
    assert(bh.extractMax() === 1)
  }

  @Test
  def decreaseKeyButNoOrderingChange() {
    val bh = new BinaryHeap[Int]()

    bh.insert(1, 0)
    bh.insert(2, 100)
    bh.insert(3, 10)

    bh.decreasePriorityTo(3, 1)
    assert(bh.max() === 2)
    assert(bh.extractMax() === 2)
    assert(bh.extractMax() === 3)
    assert(bh.extractMax() === 1)
  }

  @Test
  def increaseKey1() {
    val bh = new BinaryHeap[Int]()

    bh.insert(1, 0)
    bh.insert(2, 100)
    bh.insert(3, 10)

    bh.increasePriorityTo(3, 200)
    assert(bh.max() === 3)
    assert(bh.extractMax() === 3)
    assert(bh.extractMax() === 2)
    assert(bh.extractMax() === 1)
  }

  @Test
  def increaseKeys() {
    val bh = new BinaryHeap[Int]()

    bh.insert(1, 0)
    bh.insert(2, 100)
    bh.insert(3, 10)

    bh.increasePriorityTo(3, 200)
    bh.increasePriorityTo(2, 300)
    bh.increasePriorityTo(1, 250)

    assert(bh.max() === 2)
    assert(bh.extractMax() === 2)
    assert(bh.extractMax() === 1)
    assert(bh.extractMax() === 3)
  }

  @Test
  def samePriority() {
    val bh = new BinaryHeap[Int]()

    bh.insert(1, 0)
    bh.insert(2, 100)
    bh.insert(3, 0)

    assert(bh.max() === 2)
    assert(bh.extractMax() === 2)

    (bh.extractMax(), bh.extractMax()) should (equal(1, 3) or equal(3, 1))
  }

  @Test
  def successivelyMoreInserts() {
    for (count <- Seq(2, 4, 8, 16, 32)) {
      val bh = new BinaryHeap[Int](8)
      val trace = new ArrayBuilder[String]()
      trace += bh.toString()
      bh.checkHeapProperty()
      for (i <- 0 until count) {
        bh.insert(i, i)
        trace += bh.toString()
        bh.checkHeapProperty()
      }
      assert(bh.size === count)
      assert(bh.max() === count - 1)
      for (i <- 0 until count) {
        val actual = bh.extractMax()
        trace += bh.toString()
        bh.checkHeapProperty()
        val expected = count - i - 1
        assert(actual === expected, s"[$count] $actual did not equal $expected, heap: $bh; trace ${ trace.result().mkString("\n") }")
      }
      assert(bh.isEmpty)
    }
  }

  @Test
  def growPastCapacity4() {
    val bh = new BinaryHeap[Int](4)
    bh.insert(1, 0)
    bh.insert(2, 0)
    bh.insert(3, 0)
    bh.insert(4, 0)
    bh.insert(5, 0)
    assert(true)
  }

  @Test
  def growPastCapacity32() {
    val bh = new BinaryHeap[Int](32)
    for (i <- 0 to 32) {
      bh.insert(i, 0)
    }
    assert(true)
  }

  @Test
  def shrinkCapacity() {
    val bh = new BinaryHeap[Int](8)
    val trace = new ArrayBuilder[String]()
    trace += bh.toString()
    bh.checkHeapProperty()
    for (i <- 0 until 64) {
      bh.insert(i, i)
      trace += bh.toString()
      bh.checkHeapProperty()
    }
    assert(bh.size === 64, s"trace: ${ trace.result().mkString("\n") }")
    assert(bh.max() === 63, s"trace: ${ trace.result().mkString("\n") }")
    // shrinking happens when size is <1/4 of capacity
    for (i <- 0 until (32 + 16 + 1)) {
      val actual = bh.extractMax()
      val expected = 64 - i - 1
      trace += bh.toString()
      bh.checkHeapProperty()
      assert(actual === expected, s"$actual did not equal $expected, trace: ${ trace.result().mkString("\n") }")
    }
    assert(bh.size === 15, s"trace: ${ trace.result().mkString("\n") }")
    assert(bh.max() === 14, s"trace: ${ trace.result().mkString("\n") }")
  }

  private sealed trait HeapOp

  private sealed case class Max() extends HeapOp

  private sealed case class ExtractMax() extends HeapOp

  private sealed case class Insert(t: Long, rank: Long) extends HeapOp

  private class LongPriorityQueueReference {

    import Ordering.Implicits._

    val m = new mutable.HashMap[Long, Long]()

    def isEmpty() =
      m.size == 0

    def size() =
      m.size

    def max(): Long =
      m.toSeq.sortWith(_ > _).head._1

    def extractMax(): Long = {
      val max = m.toSeq.sortWith(_ > _).head._1
      m -= max
      max
    }

    def insert(t: Long, rank: Long) {
      m += (t -> rank)
    }
  }

  @Test
  def sameAsReferenceImplementation() {
    import Gen._

    val ops = for {
      maxOrExtract <- buildableOfN(1024, oneOfGen(const(Max()), const(ExtractMax())))
      ranks <- distinctBuildableOfN(1024, arbitrary[Long])
      inserts = ranks.map(r => Insert(r, r))
      ret <- Gen.shuffle(inserts ++ maxOrExtract)
    } yield ret

    forAll(ops) { opList =>
      val bh = new BinaryHeap[Long]()
      val ref = new LongPriorityQueueReference()
      val trace = new ArrayBuilder[String]()
      trace += bh.toString()
      opList.foreach {
        case Max() =>
          if (bh.isEmpty && ref.isEmpty)
            assert(true, s"trace; ${ trace.result().mkString("\n") }")
          else
            assert(bh.max() === ref.max(), s"trace; ${ trace.result().mkString("\n") }")
          trace += bh.toString()
          bh.checkHeapProperty()
        case ExtractMax() =>
          if (bh.isEmpty && ref.isEmpty)
            assert(true, s"trace; ${ trace.result().mkString("\n") }")
          else
            assert(bh.max() === ref.max(), s"trace; ${ trace.result().mkString("\n") }")
          trace += bh.toString()
          bh.checkHeapProperty()
        case Insert(t, rank) =>
          bh.insert(t, rank)
          ref.insert(t, rank)
          trace += bh.toString()
          bh.checkHeapProperty()
          assert(bh.size === ref.size, s"trace; ${ trace.result().mkString("\n") }")
      }
      true
    }.check()
  }

}
