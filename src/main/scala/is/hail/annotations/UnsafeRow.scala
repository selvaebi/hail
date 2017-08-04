package is.hail.annotations

import is.hail.expr._
import is.hail.variant.{AltAllele, Genotype, Locus, Variant}
import org.apache.spark.sql.Row

class UnsafeRow(@transient var t: TStruct, val mb: MemoryBlock, val mbOffset: Int) extends Row {

  def length: Int = t.size

  private def readBinary(offset: Int): Array[Byte] = {
    val start = mb.loadInt(offset)
    assert(offset > 0 && (offset & 0x3) == 0, s"invalid binary start: $offset")
    val binLength = mb.loadInt(start)
    val b = mb.loadBytes(start + 4, binLength)

    b
  }

  private def readArray(offset: Int, t: Type): IndexedSeq[Any] = {
    val start = mb.loadInt(offset)

    assert(start > 0 && (start & 0x3) == 0, s"invalid array start: $offset")

    val arrLength = mb.loadInt(start)
    val missingBytes = (arrLength + 7) / 8
    val elemsStart = UnsafeUtils.roundUpAlignment(start + 4 + missingBytes, t.alignment)
    val eltSize = UnsafeUtils.arrayElementSize(t)

    val a = new Array[Any](arrLength)

    var i = 0
    while (i < arrLength) {

      val byteIndex = i / 8
      val bitShift = i & 0x7
      val missingByte = mb.loadByte(start + 4 + byteIndex)
      val isMissing = (missingByte & (0x1 << bitShift)) != 0

      if (!isMissing)
        a(i) = read(elemsStart + i * eltSize, t)

      i += 1
    }

    a
  }

  private def readStruct(offset: Int, t: TStruct): UnsafeRow = {
    new UnsafeRow(t, mb, offset)
  }

  private def read(offset: Int, t: Type): Any = {
    t match {
      case TBoolean =>
        val b = mb.loadByte(offset)
        assert(b == 0 || b == 1, s"invalid boolean byte $b from offset $offset")
        b == 1
      case TInt32 | TCall => mb.loadInt(offset)
      case TInt64 => mb.loadLong(offset)
      case TFloat32 => mb.loadFloat(offset)
      case TFloat64 => mb.loadDouble(offset)
      case TArray(elementType) => readArray(offset, elementType)
      case TSet(elementType) => readArray(offset, elementType).toSet
      case TString => new String(readBinary(offset))
      case td: TDict =>
        readArray(offset, td.elementType).asInstanceOf[IndexedSeq[Row]].map(r => (r.get(0), r.get(1))).toMap
      case struct: TStruct =>
        readStruct(offset, struct)
      case TVariant => Variant.fromRow(readStruct(offset, TVariant.representation))
      case TLocus => Locus.fromRow(readStruct(offset, TLocus.representation))
      case TAltAllele => AltAllele.fromRow(readStruct(offset, TAltAllele.representation))
      case TGenotype => Genotype.fromRow(readStruct(offset, TGenotype.representation))
      case TInterval => Locus.intervalFromRow(readStruct(offset, TInterval.representation))
    }
  }

  private def assertDefined(i: Int) {
    if (isNullAt(i))
      throw new NullPointerException(s"null value at index $i")
  }

  def get(i: Int): Any = {
    val offset = t.byteOffsets(i)
    if (isNullAt(i))
      null
    else
      read(mbOffset + offset, t.fields(i).typ)
  }

  def copy(): Row = new UnsafeRow(t, mb.copy(), mbOffset)

  override def getInt(i: Int): Int = {
    assertDefined(i)
    val offset = t.byteOffsets(i)
    mb.loadInt(mbOffset + offset)
  }

  override def getLong(i: Int): Long = {
    assertDefined(i)
    val offset = t.byteOffsets(i)
    mb.loadLong(mbOffset + offset)
  }

  override def getFloat(i: Int): Float = {
    assertDefined(i)
    val offset = t.byteOffsets(i)
    mb.loadFloat(mbOffset + offset)
  }

  override def getDouble(i: Int): Double = {
    assertDefined(i)
    val offset = t.byteOffsets(i)
    mb.loadDouble(mbOffset + offset)
  }

  override def getBoolean(i: Int): Boolean = {
    getByte(i) == 1
  }

  override def getByte(i: Int): Byte = {
    assertDefined(i)
    val offset = t.byteOffsets(i)
    mb.loadByte(mbOffset + offset)
  }

  override def isNullAt(i: Int): Boolean = {
    if (i < 0 || i >= t.size)
      throw new IndexOutOfBoundsException(i.toString)
    val byteIndex = i / 8
    val bitShift = i & 0x7
    (mb.loadByte(mbOffset + byteIndex) & (0x1 << bitShift)) != 0
  }
}
