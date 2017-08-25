package is.hail.methods

import is.hail.HailContext
import is.hail.distributedmatrix.{BlockMatrixIsDistributedMatrix, DistributedMatrix}
import is.hail.distributedmatrix.DistributedMatrix.implicits._
import is.hail.stats.RegressionUtils
import is.hail.utils._
import is.hail.stats._
import is.hail.variant.{Variant, VariantDataset}
import breeze.linalg.{*, DenseMatrix}
import is.hail.annotations.Annotation
import is.hail.expr.TVariant
import org.apache.spark.mllib.linalg.distributed.{BlockMatrix, IndexedRow, IndexedRowMatrix}
import org.apache.spark.mllib.linalg.{Vectors, DenseMatrix => SparkDenseMatrix, DenseVector => SparkDenseVector, Matrix => SparkMatrix}
import org.apache.hadoop.io._
import org.json4s._

object LDMatrix {
  /**
    * Computes the LD matrix for the given VDS.
    * @param vds VDS on which to compute Pearson correlation between pairs of variants.
    * @return An LDMatrix.
    */
  def apply(vds : VariantDataset, optComputeLocally: Option[Boolean]): LDMatrix = {
    val nSamples = vds.nSamples
    val nVariants = vds.countVariants()

    val filteredNormalizedHardCalls = vds.rdd.flatMap { 
      case (v, (va, gs)) => RegressionUtils.normalizedHardCalls(gs, nSamples).map(x => (v, x))
    }
    
    val variantsKept = filteredNormalizedHardCalls.map(_._1).collect()
    assert(variantsKept.isSorted, "ld_matrix: Array of variants is not sorted. This is a bug.")

    val normalizedIndexedRows = filteredNormalizedHardCalls.map(_._2).zipWithIndex()
      .map{ case (values, idx) => IndexedRow(idx, Vectors.dense(values))}
    val normalizedBlockMatrix = new IndexedRowMatrix(normalizedIndexedRows).toBlockMatrixDense()

    val nVariantsKept = variantsKept.length
    val nVariantsDropped = nVariants - nVariantsKept

    info(s"Computing LD matrix with ${variantsKept.length} variants using $nSamples samples. $nVariantsDropped variants were dropped.")

    val localBound = 5000 * 5000
    val nEntries: Long = nVariantsKept * nSamples
    val nSamplesInverse = 1.0 / nSamples

    val computeLocally = optComputeLocally.getOrElse(nEntries <= localBound)

    var indexedRowMatrix: IndexedRowMatrix = null

    if (computeLocally) {
      val localMat: SparkDenseMatrix = normalizedBlockMatrix.toLocalMatrix().asInstanceOf[SparkDenseMatrix]
      val product = localMat multiply localMat.transpose
      indexedRowMatrix =
        BlockMatrixIsDistributedMatrix.from(vds.sparkContext, product, normalizedBlockMatrix.rowsPerBlock,
          normalizedBlockMatrix.colsPerBlock).toIndexedRowMatrix()
    } else {
      import is.hail.distributedmatrix.DistributedMatrix.implicits._
      val dm = DistributedMatrix[BlockMatrix]
      import dm.ops._
      indexedRowMatrix = (normalizedBlockMatrix * normalizedBlockMatrix.t)
        .toIndexedRowMatrix()
    }

    val scaledIndexedRowMatrix = new IndexedRowMatrix(indexedRowMatrix.rows
      .map{case IndexedRow(idx, vals) => IndexedRow(idx, vals.map(d => d * nSamplesInverse))})
    
    LDMatrix(scaledIndexedRowMatrix, variantsKept, nSamples)
  }

  private val metadataRelativePath = "/metadata.json"
  private val matrixRelativePath = "/matrix"
  
  def read(hc: HailContext, uri: String): LDMatrix = {
    val hadoop = hc.hadoopConf
    hadoop.mkDir(uri)

    val rdd = hc.sc.sequenceFile[LongWritable, ArrayPrimitiveWritable](uri+matrixRelativePath).map { case (lw, apw) =>
      IndexedRow(lw.get(), new SparkDenseVector(apw.get().asInstanceOf[Array[Double]]))
    }

    val LDMatrixMetadata(variants, nSamples) =
      hadoop.readTextFile(uri+metadataRelativePath) { isr =>
        jackson.Serialization.read[LDMatrixMetadata](isr)
      }

    new LDMatrix(new IndexedRowMatrix(rdd), variants, nSamples)
  }
}

/**
  *
  * @param matrix Spark IndexedRowMatrix. Entry (i, j) encodes the r value between variants i and j.
  * @param variants Array of variants indexing the rows and columns of the matrix.
  * @param nSamplesUsed Number of samples used to compute this matrix.
  */
case class LDMatrix(matrix: IndexedRowMatrix, variants: Array[Variant], nSamplesUsed: Int) {
  import LDMatrix._

  def toLocalMatrix: SparkMatrix = {
    matrix.toBlockMatrixDense().toLocalMatrix()
  }
  
  def eigen(optNEigs: Option[Int]): Eigen = {
    val L = matrix.toLocalMatrix().asBreeze().asInstanceOf[DenseMatrix[Double]]

    info(s"Computing eigenvectors of LD matrix...")
    val eig = printTime(eigSymD(L))
    
    val maxRank = variants.length min nSamplesUsed
    val nEigs = optNEigs.getOrElse(maxRank)
    optNEigs.foreach( k => if (k > nEigs) info(s"Requested $k evects but maximum rank is $maxRank.") )
    
    info(s"Eigendecomposition complete, returning $nEigs eigenvectors.")
    
    val m = L.rows
    assert(m == L.cols && m == eig.eigenvectors.cols)
    
    val (evects, evals) =
      if (nEigs == m)
        (eig.eigenvectors, eig.eigenvalues)
      else
        (eig.eigenvectors(::, (m - nEigs) until m).copy, eig.eigenvalues((m - nEigs) until m).copy)
    
    Eigen(TVariant, variants.map(_.asInstanceOf[Annotation]), evects, evals)
  }
  
  def write(uri: String) {
    val hadoop = matrix.rows.sparkContext.hadoopConfiguration
    hadoop.mkDir(uri)

    matrix.rows.map { case IndexedRow(i, v) => (new LongWritable(i), new ArrayPrimitiveWritable(v.toArray)) }
      .saveAsSequenceFile(uri+matrixRelativePath)

    hadoop.writeTextFile(uri+metadataRelativePath) { os =>
      jackson.Serialization.write(
        LDMatrixMetadata(variants, nSamplesUsed),
        os)
    }
  }
}

case class LDMatrixMetadata(variants: Array[Variant], nSamples: Int)
