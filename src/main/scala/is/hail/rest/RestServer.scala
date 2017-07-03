package is.hail.rest

import breeze.linalg.DenseMatrix
import is.hail.annotations.Annotation
import is.hail.stats.RegressionUtils
import is.hail.variant.VariantDataset
import org.http4s.server.blaze.BlazeBuilder

object PhenotypeTable {
  def apply(vds: VariantDataset, covExpr: Array[String]): PhenotypeTable = {
    // FIXME wrong behavior, want to keep missing
    val (y, cov, completeSamples) = RegressionUtils.getPhenoCovCompleteSamples(vds, covExpr(0), covExpr)
    
    new PhenotypeTable(completeSamples.toArray, covExpr, cov)
  }
}

case class PhenotypeTable(samples: Array[Annotation], phenotypes: Array[String], data: DenseMatrix[Double]) {
  def selectCovariates(covariates: Array[String]): PhenotypeTable = ???
  
  def selectSamples(samples: Array[Annotation]): PhenotypeTable = ???
}

object RestServer {
  def apply(vds: VariantDataset, covariates: Array[String], port: Int = 8080, maxWidth: Int = 600000, hardLimit: Int = 100000) {
    val phenoTable = PhenotypeTable(vds, covariates)
    val restService = new RestService(vds, phenoTable, maxWidth, hardLimit)
    
    val task = BlazeBuilder.bindHttp(port, "0.0.0.0")
      .mountService(restService.service, "/")
      .run
    task.awaitShutdown()
  }
}