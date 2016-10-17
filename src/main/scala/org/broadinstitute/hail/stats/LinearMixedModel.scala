package org.broadinstitute.hail.stats

import breeze.linalg._
import breeze.numerics.sqrt
import org.apache.spark.rdd.RDD
import org.broadinstitute.hail.annotations._
import org.broadinstitute.hail.expr.{TDouble, TStruct, Type}
import org.broadinstitute.hail.methods.{ComputeRRM, ToSparseRDD}
import org.broadinstitute.hail.variant.{Variant, VariantDataset}
import org.broadinstitute.hail.utils._

object LMM {
  def progressReport(msg: String) = {
    val prog = s"\nlmmreg progress: $msg, ${ formatTime(System.nanoTime()) }\n"
    //    println(prog)
    log.info(prog)
  }

  def apply(vdsKernel: VariantDataset,
    vdsAssoc: VariantDataset,
    C: DenseMatrix[Double],
    y: DenseVector[Double],
    optDelta: Option[Double] = None,
    useML: Boolean = false): LMMResult = {

    val n = y.length

    progressReport(s"Computing kernel for $n samples...")

    val (kernel, m) = ComputeRRM.withoutBlocks(vdsKernel)
    // val (kernel, m) = ComputeRRM.withBlocks(vdsKernel)
    assert(kernel.rows == n && kernel.cols == n)

    progressReport(s"RRM computed using $m variants. Computing eigenvectors... ") // should use better Lapack method

    val eigK = eigSymD(kernel)
    val Ut = eigK.eigenvectors.t
    val S = eigK.eigenvalues //place S in global annotations?
    assert(S.length == n)

    progressReport("Largest evals: " + ((n - 1) to math.max(0, n - 10) by -1).map(S(_).formatted("%.5f")).mkString(", "))
    progressReport("Smallest evals: " + (0 until math.min(n, 10)).map(S(_).formatted("%.5f")).mkString(", "))

    progressReport(s"Estimating delta using ${if (useML) "ML" else "REML"}... ")

    val UtC = Ut * C
    val Uty = Ut * y

    val diagLMM = DiagLMM(UtC, Uty, S, optDelta, useML)

    progressReport(s"delta = ${diagLMM.delta}")

//  // temporary
//  val header = "rank\teval"
//  val evalString = (0 until n).map(i => s"$i\t${S(i)}").mkString("\n")
//  log.info(s"\nEIGENVALUES\n$header\n$evalString\n\n")

    progressReport(s"Computing LMM statistics for each variant...")

    val T = Ut(::,*) :* diagLMM.sqrtInvD
    val Qt = qr.reduced.justQ(diagLMM.TC).t
    val QtTy = Qt * diagLMM.Ty
    val TyQtTy = (diagLMM.Ty dot diagLMM.Ty) - (QtTy dot QtTy)

    val G = ToSparseRDD(vdsAssoc)
    val sc = G.sparkContext
    val TBc = sc.broadcast(T)

    val scalerLMMBc = sc.broadcast(ScalerLMM(diagLMM.Ty, diagLMM.TyTy, Qt, QtTy, TyQtTy, diagLMM.logNullS2, useML))

    val lmmResult = G.mapValues(x => scalerLMMBc.value.likelihoodRatioTest(TBc.value * x))

    println(formatTime(System.nanoTime()))

    LMMResult(diagLMM, lmmResult)
  }
}

object DiagLMM {
  def apply(C: DenseMatrix[Double], y: DenseVector[Double], S: DenseVector[Double], optDelta: Option[Double] = None, useML: Boolean = false): DiagLMM = {
    require(C.rows == y.length)

    val delta = optDelta.getOrElse(fitDelta(C, y, S, useML)._1)

    val n = y.length
    val sqrtInvD = sqrt(S + delta).map(1 / _)
    val TC = C(::, *) :* sqrtInvD
    val Ty = y :* sqrtInvD
    val TyTy = Ty dot Ty
    val TCTy = TC.t * Ty
    val TCTC = TC.t * TC
    val b = TCTC \ TCTy
    val s2 = (TyTy - (TCTy dot b)) / (if (useML) n else n - C.cols)

    DiagLMM(b, s2, math.log(s2), delta, sqrtInvD, TC, Ty, TyTy, useML)
  }

  def fitDelta(C: DenseMatrix[Double], y: DenseVector[Double], S: DenseVector[Double], useML: Boolean): (Double, IndexedSeq[(Double, Double)]) = {

    val logmin = -10
    val logmax = 10
    val pointsPerUnit = 100 // number of points per unit of log space

    val grid = (logmin * pointsPerUnit to logmax * pointsPerUnit).map(_.toDouble / pointsPerUnit) // avoids rounding of (logmin to logmax by logres)

    val n = y.length
    val c = C.cols

    // up to constant shift and scale by 2
    def negLogLkhd(delta: Double, useML: Boolean): Double = {
      val D = S + delta
      val dy = y :/ D
      val ydy = y dot dy
      val Cdy = C.t * dy
      val CdC = C.t * (C(::, *) :/ D)
      val b = CdC \ Cdy
      val r = ydy - (Cdy dot b)

      if (useML)
        sum(breeze.numerics.log(D)) + n * math.log(r)
      else
        sum(breeze.numerics.log(D)) + (n - c) * math.log(r) + logdet(CdC)._2
    }

    val gridVals = grid.map(logDelta => (logDelta, negLogLkhd(math.exp(logDelta), useML)))

    // temporarily included to inspect delta optimization
    // perhaps interesting to return "curvature" at maximum
    // val header = "logDelta\tnegLogLkhd"
    // val gridValsString = gridVals.map{ case (d, nll) => s"${d.formatted("%.4f")}\t$nll" }.mkString("\n")
    // log.info(s"\nDELTAVALUES\n$header\n$gridValsString\n\n")

    val logDelta = gridVals.minBy(_._2)._1

    if (logDelta == logmin)
      fatal(s"failed to fit delta: maximum likelihood at lower search boundary e^$logmin")
    else if (logDelta == logmax)
      fatal(s"failed to fit delta: maximum likelihood at upper search boundary e^$logmax")

    (math.exp(logDelta), gridVals)
  }
}

case class DiagLMM(
  nullB: DenseVector[Double],
  nullS2: Double,
  logNullS2: Double,
  delta: Double,
  sqrtInvD: DenseVector[Double],
  TC: DenseMatrix[Double],
  Ty: DenseVector[Double],
  TyTy: Double,
  useML: Boolean)

case class ScalerLMM(
  y: DenseVector[Double],
  yy: Double,
  Qt: DenseMatrix[Double],
  Qty: DenseVector[Double],
  yQty: Double,
  logNullS2: Double,
  useML: Boolean) {

  def likelihoodRatioTest(x: Vector[Double]): LMMStat = {

    val n = y.length
    val Qtx = Qt * x
    val xQtx: Double = (x dot x) - (Qtx dot Qtx)
    val xQty: Double = (x dot y) - (Qtx dot Qty)

    val b: Double = xQty / xQtx
    val s2 = (yQty - xQty * b) / (if (useML) n else n - Qt.rows)
    val chi2 = n * (logNullS2 - math.log(s2))
    val p = chiSquaredTail(1, chi2)

    LMMStat(b, s2, chi2, p)
  }
}


object LMMStat {
  def `type`: Type = TStruct(
    ("beta", TDouble),
    ("sigmaG2", TDouble),
    ("chi2", TDouble),
    ("pval", TDouble))
}

case class LMMStat(b: Double, s2: Double, chi2: Double, p: Double) {
  def toAnnotation: Annotation = Annotation(b, s2, chi2, p)
}

case class LMMResult(diagLMM: DiagLMM, rdd: RDD[(Variant, LMMStat)])