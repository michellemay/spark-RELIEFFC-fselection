package org.apache.spark.ml.feature

import org.apache.log4j.{Level, LogManager}
import org.apache.spark.sql.functions._
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import org.apache.spark.sql.types._
import org.joda.time.format.DateTimeFormat
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.ml.util._
import org.apache.spark.annotation.Experimental
import org.apache.spark.annotation.Since
import org.apache.spark.mllib.util.MLUtils
import scala.collection.mutable.WrappedArray
import org.apache.spark.ml.linalg.VectorUDT
import org.apache.spark.mllib.linalg.{Vectors => OldVectors}

/**
  * Loads various test datasets
  */
object TestHelper {

  final val SPARK_CTX: SparkContext = null //createSparkContext()
  final val FILE_PREFIX = ""
  final val ISO_DATE_FORMAT = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss")
  final val NULL_VALUE = "?"

  // This value is used to represent nulls in string columns
  final val MISSING = "__MISSING_VALUE__"
  final val CLEAN_SUFFIX: String = "_CLEAN"
  final val INDEX_SUFFIX: String = "_IDX"
  
  
  def log2(x: Float) = { math.log(x) / math.log(2) }



  def cleanLabelCol(dataframe: DataFrame, labelColumn: String): DataFrame = {
    val df = dataframe
      .withColumn(labelColumn + CLEAN_SUFFIX, when(col(labelColumn).isNull, lit(MISSING)).otherwise(col(labelColumn)))

    convertLabelToIndex(df, labelColumn + CLEAN_SUFFIX, labelColumn + INDEX_SUFFIX)
  }

  def cleanNumericCols(dataframe: DataFrame, numericCols: Array[String]): DataFrame = {
    var df = dataframe
    numericCols.foreach(column => {
      df = df.withColumn(column + CLEAN_SUFFIX, when(col(column).isNull, lit(Double.NaN)).otherwise(col(column)))
    })
    df
  }

  def convertLabelToIndex(df: DataFrame, inputCol: String, outputCol: String): DataFrame = {

    val labelIndexer = new StringIndexer()
      .setInputCol(inputCol)
      .setOutputCol(outputCol).fit(df)

    labelIndexer.transform(df)
  }

  def createSparkContext() = {
    // the [n] corresponds to the number of worker threads and should correspond ot the number of cores available.
    val conf = new SparkConf().setAppName("test-spark")//.setMaster("local[4]")
    // Changing the default parallelism gave slightly different results and did not do much for performance.
    //conf.set("spark.default.parallelism", "2")
    val sc = new SparkContext(conf)
    LogManager.getRootLogger.setLevel(Level.WARN)
    sc
  }
  
  /** @return standard csv data from the repo.
    */
  def readData(sqlContext: SQLContext, file: String, header: Boolean = true, format: String = "csv"): DataFrame = {
      if(format == "libsvm"){
        // patched till problems with multiple input paths are fixed
        val rowRDD = MLUtils.loadLibSVMFile(sqlContext.sparkSession.sparkContext, FILE_PREFIX + file)
            .map { l => Row(l.label, l.features.asML) }
        val schema = new StructType()
            .add(StructField("label", DoubleType, true))
            .add(StructField("features", new VectorUDT(), true))
        sqlContext.createDataFrame(rowRDD, schema)
      } else {
        val df = sqlContext.read.format(format)
          .option("header", header.toString) // Use first line of all files as header
          .option("inferSchema", "true") // Automatically infer data types
          .load(FILE_PREFIX + file)
        df 
      }
       
  }
  
  /** @return dataset with 3 double columns. The first is the label column and contain null.
    */
  def readNullLabelTestData(sqlContext: SQLContext): DataFrame = {
    val data = SPARK_CTX.textFile(FILE_PREFIX + "null_label_test.data")
    val nullable = true

    val schema = StructType(List(
      StructField("label_IDX", DoubleType, nullable),
      StructField("col1", DoubleType, nullable),
      StructField("col2", DoubleType, nullable)
    ))
    // ints and dates must be read as doubles
    val rows = data.map(line => line.split(",").map(elem => elem.trim))
      .map(x => {Row.fromSeq(Seq(asDouble(x(0)), asDouble(x(1)), asDouble(x(2))))})

    sqlContext.createDataFrame(rows, schema)
  }

  private def asDateDouble(isoString: String) = {
    if (isoString == NULL_VALUE) Double.NaN
    else ISO_DATE_FORMAT.parseDateTime(isoString).getMillis.toString.toDouble
  }

  // label cannot currently have null values - see #8.
  private def asString(value: String) = if (value == NULL_VALUE) null else value
  private def asDouble(value: String) = if (value == NULL_VALUE) Double.NaN else value.toDouble
}
