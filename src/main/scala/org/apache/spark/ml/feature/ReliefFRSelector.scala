/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ml.feature

import org.apache.hadoop.fs.Path
import org.apache.spark.annotation.{ Since, Experimental }
import org.apache.spark.ml._
import org.apache.spark.ml.attribute.{ AttributeGroup, _ }
import org.apache.spark.ml.linalg.{ Vector, VectorUDT }
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared._
import org.apache.spark.ml.util._
import org.apache.spark.mllib.feature
import org.apache.spark.mllib.linalg.{ Vectors => OldVectors }
import org.apache.spark.mllib.regression.{ LabeledPoint => OldLabeledPoint }
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{ DoubleType, StructField, StructType }
import org.apache.spark.broadcast.Broadcast
import scala.collection.mutable.Queue
import breeze.linalg.Axis
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.sql.types.LongType
import org.apache.spark.sql.types.DataTypes
import scala.collection.mutable.WrappedArray
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.types.FloatType
import breeze.linalg.VectorBuilder
import breeze.linalg.CSCMatrix
import org.apache.spark.ml.linalg.SparseVector
import scala.collection.mutable.HashMap
import breeze.linalg.{Matrix => BM, Vector => BV, DenseMatrix => BDM, DenseVector => BDV, SparseVector => BSV}
import breeze.generic.UFunc
import breeze.generic.MappingUFunc
import breeze.linalg.support._
import scala.collection.immutable.HashSet
import org.apache.spark.util.SizeEstimator
import scala.collection.mutable.ArrayBuffer

/**
 * Params for [[ReliefFRSelector]] and [[ReliefFRSelectorModel]].
 */
private[feature] trait ReliefFRSelectorParams extends Params
    with HasInputCol with HasOutputCol with HasLabelCol with HasSeed {

  /**
   * Relief with redundancy removal criterion used to rank the features.
   * Relief relies on LSH to perform efficient nearest neighbor searches.
   *
   * @group param
   */
  
  /**
   * Param for the number of hash tables used in LSH OR-amplification.
   *
   * LSH OR-amplification can be used to reduce the false negative rate. Higher values for this
   * param lead to a reduced false negative rate, at the expense of added computational complexity.
   * @group param
   */
  final val numHashTables: IntParam = new IntParam(this, "numHashTables", "number of hash " +
    "tables, where increasing number of hash tables lowers the false negative rate, and " +
    "decreasing it improves the running performance", ParamValidators.gtEq(0))

  /** @group getParam */
  final def getNumHashTables: Int = $(numHashTables)
  setDefault(numHashTables -> 1)
  
    /**
   * Param for the size of signatures built inside the hash tables.
   *
   * Higher values for this param lead to a reduced false negative rate, 
   * at the expense of added computational complexity.
   * @group param
   */
  final val signatureSize: IntParam = new IntParam(this, "signatureSize", "signature size or" +
    "number of random projections, where increasing size means lowers the false negative rate, and " +
    "decreasing it improves the running performance", ParamValidators.gt(0))

  /** @group getParam */
  final def getSignatureSize: Int = $(signatureSize)
  setDefault(signatureSize -> 16)
  
  /**
   * The length of each hash bucket, a larger bucket lowers the false negative rate. The number of
   * buckets will be `(max L2 norm of input vectors) / bucketLength`.
   *
   *
   * If input vectors are normalized, 1-10 times of pow(numRecords, -1/inputDim) would be a
   * reasonable value
   * @group param
   */
  val bucketLength: DoubleParam = new DoubleParam(this, "bucketLength",
    "the length of each hash bucket, a larger bucket lowers the false negative rate.",
    ParamValidators.gt(0))

  /** @group getParam */
  final def getBucketLength: Double = $(bucketLength)  
  setDefault(bucketLength -> 4)

  final val sparseSpeedup: DoubleParam = new DoubleParam(this, "sparseSpeedup", "", ParamValidators.gtEq(0))

  /** @group getParam */
  final def getSparseSpeedup: Double = $(sparseSpeedup)

  setDefault(sparseSpeedup -> 0)
  
  
  /**
   * Number of features that selector will select (ordered by statistic value descending). If the
   * number of features is < numTopFeatures, then this will select all features. The default value
   * of numTopFeatures is 50.
   *
   * @group param
   */
  final val numTopFeatures = new IntParam(this, "numTopFeatures",
    "Number of features that selector will select, ordered by statistics value descending. If the" +
      " number of features is < numTopFeatures, then this will select all features.",
    ParamValidators.gtEq(1))
  setDefault(numTopFeatures -> 10)
  
  final val numNeighbors = new IntParam(this, "numNeighbors", "",
    ParamValidators.gtEq(1))
  setDefault(numNeighbors -> 10)
  
  final val estimationRatio: DoubleParam = new DoubleParam(this, "estimationRatio", "", ParamValidators.inRange(0,1))
  setDefault(estimationRatio -> 0.25)
  
  final val batchSize: DoubleParam = new DoubleParam(this, "batchSize", "", ParamValidators.inRange(0,1))
  setDefault(batchSize -> 0.1)
  
  final val queryStep: IntParam = new IntParam(this, "queryStep", "", ParamValidators.gtEq(1))
  setDefault(queryStep -> 2)  
  
  final val lowerFeatureThreshold: DoubleParam = new DoubleParam(this, "lowerFeatureThreshold", "", ParamValidators.gtEq(1))
  setDefault(lowerFeatureThreshold -> 3.0)
  
  final val lowerDistanceThreshold: DoubleParam = new DoubleParam(this, "lowerDistanceThreshold", "", ParamValidators.inRange(0,1))
  setDefault(lowerDistanceThreshold -> 0.8)
  
  final val redundancyRemoval: BooleanParam = new BooleanParam(this, "redundancyRemoval", "")
  setDefault(redundancyRemoval -> true)
  
  final val discreteData: BooleanParam = new BooleanParam(this, "discreteData", "")
  setDefault(discreteData -> false)
  
}

/**
 * :: Experimental ::
 * Relief feature selection, which relies on distance measurements among neighbors to weight features.
 */
@Experimental
final class ReliefFRSelector @Since("1.6.0") (@Since("1.6.0") override val uid: String = Identifiable.randomUID("ReliefFRSelector"))
    extends Estimator[ReliefFRSelectorModel] with ReliefFRSelectorParams with DefaultParamsWritable {

  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)
  def setLabelCol(value: String): this.type = set(labelCol, value)
  def setInputCol(value: String): this.type = set(inputCol, value)
  def setSeed(value: Long): this.type = set(seed, value)
  def setDiscreteData(value: Boolean): this.type = set(discreteData, value)
  /** @group LSH params */
  def setNumHashTables(value: Int): this.type = set(numHashTables, value)
  def setSignatureSize(value: Int): this.type = set(signatureSize, value)  
  def setBucketLength(value: Double): this.type = set(bucketLength, value)
  def setQueryStep(value: Int): this.type = set(queryStep, value) 
  def setSparseSpeedup(value: Double): this.type = set(sparseSpeedup, value)
  /** @group RELIEF params */
  def setNumTopFeatures(value: Int): this.type = set(numTopFeatures, value)
  def setNumNeighbors(value: Int): this.type = set(numNeighbors, value)
  def setEstimationRatio(value: Double): this.type = set(estimationRatio, value)
  def setBatchSize(value: Double): this.type = set(batchSize, value)
  def setLowerFeatureThreshold(value: Double): this.type = set(lowerFeatureThreshold, value)
  def setLowerDistanceThreshold(value: Double): this.type = set(lowerDistanceThreshold, value)
  def setRedundancyRemoval(value: Boolean): this.type = set(redundancyRemoval, value)
  
  
  override def transformSchema(schema: StructType): StructType = {
    SchemaUtils.checkColumnType(schema, $(inputCol), new VectorUDT)
    SchemaUtils.checkColumnType(schema, $(labelCol), DoubleType)
    SchemaUtils.appendColumn(schema, $(outputCol), new VectorUDT)
  }
  
@Since("2.0.0")
  override def fit(dataset: Dataset[_]): ReliefFRSelectorModel = {
    
    transformSchema(dataset.schema, logging = true)
    
    // Get Hash Value of the key 
    val hashOutputCol = "hashRELIEF_" + System.currentTimeMillis()
    val modelDataset: DataFrame = if($(numHashTables) > 0){
      val now = System.currentTimeMillis
      val brp = new BucketedRandomLSH()
        .setInputCol($(inputCol))
        .setOutputCol(hashOutputCol)
        .setSparseSpeedup($(sparseSpeedup))
        .setNumHashTables($(numHashTables))
        .setBucketLength($(bucketLength))
        .setSignatureSize($(signatureSize))
        .setSeed($(seed))
      val LSHmodel = brp.fit(dataset)
      val hashRuntime = (System.currentTimeMillis - now) / 1000
      logInfo("Hash model trained in " + hashRuntime + "s")
      
      // Generate hash code for the complete dataset
      if (!dataset.columns.contains(hashOutputCol)) {
          LSHmodel.transform(dataset).cache()
        } else {
          dataset.toDF()
        }  
    } else {
      dataset.toDF()
    }
    
    // Get some basic information about the dataset
    val sc = dataset.sparkSession.sparkContext
    val spark = dataset.sparkSession.sqlContext
    val nElems = modelDataset.count() // needed to persist the training set
    val first = modelDataset.head().getAs[Vector]($(inputCol))
    val sparse = first.isInstanceOf[SparseVector]
    val nFeat = first.size
    val lowerFeat = math.max($(numTopFeatures), math.round($(lowerFeatureThreshold).toFloat * $(numTopFeatures))) // 0 is the min, 0.5 the median
    val priorClass = modelDataset.select($(labelCol)).rdd.map{ case Row(label: Double) => label }
        .countByValue()
        .mapValues(v => (v.toDouble / nElems).toFloat)
        .map(identity).toMap
        
    val schema = new StructType()
            .add(StructField("id2", IntegerType, true))
            .add(StructField("score2", FloatType, true))
    val weights = Array.fill((1 / $(batchSize)).toInt)($(batchSize))
    val batches = modelDataset.sample(false, $(estimationRatio)).randomSplit(weights, $(seed))
    var featureWeights: BV[Float] = if (sparse) BSV.zeros(nFeat) else BV.zeros(nFeat)
    var jointMatrix: BM[Double] = CSCMatrix.zeros(nFeat, nFeat) 
    var marginalVector: BV[Double] = if (sparse) BSV.zeros(nFeat) else BV.zeros(nFeat)
    var topFeatures: Set[Int] = Set.empty
    var total = 0L // total number of comparisons at the collision level
    val results: Array[Dataset[_]] = Array.fill(batches.size)(spark.emptyDataFrame)
    var finalWeights: Dataset[_] = spark.emptyDataFrame
    logInfo("Number of batches to be computed in RELIEF: " + results.size)
        
    for(i <- 0 until batches.size) {
      val start = System.currentTimeMillis
      // Index query objects and compute the table that indicates where are located its neighbors
      val idxModelQuery = batches(i).withColumn("UniqueID", monotonically_increasing_id).cache()
      val query = if ($(numHashTables) > 0) idxModelQuery.select(col("UniqueID"), col($(inputCol)), col($(labelCol)), col(hashOutputCol)) else
            idxModelQuery.select(col("UniqueID"), col($(inputCol)), col($(labelCol)))
      val lquery = query.collect()
      println("size: " + lquery.length)
      logInfo("Estimated size for broadcasted query: " + SizeEstimator.estimate(lquery)) 
      val bFullQuery: Broadcast[Array[Row]] = sc.broadcast(lquery)
          
      val neighbors: RDD[(Long, Map[Int, Iterable[Int]])] = approxNNByPartition(idxModelQuery, 
          bFullQuery, $(numNeighbors) * priorClass.size, hashOutputCol)
      
      val bNeighborsTable: Broadcast[Map[Long, Map[Int, Iterable[Int]]]] = 
          sc.broadcast(neighbors.collectAsMap().toMap)
      
      val (rawWeights: RDD[(Int, Float)], partialJoint, partialMarginal, partialCount) =  
          if (!sparse) computeReliefWeights(
              idxModelQuery.select($(inputCol), $(labelCol)), bFullQuery, bNeighborsTable, 
          topFeatures, priorClass, nFeat, nElems)
          else  computeReliefWeightsSparse(
              idxModelQuery.select($(inputCol), $(labelCol)), bFullQuery, bNeighborsTable, 
          topFeatures, priorClass, nFeat, nElems)
      
      // Normalize previous results and return the best features
      results(i) = spark.createDataFrame(rawWeights.map{ case(k,v) => Row(k,v) }, schema).cache()   
      if(results(i).count > 0){ // call the action required to persist data
        val normalized = normalizeRankingDF(results(i))
        implicit def scoreOrder: Ordering[Row] = Ordering.by{ _.getAs[Float]("score2") }
        topFeatures = normalized.rdd.takeOrdered(lowerFeat)(scoreOrder.reverse).map { _.getAs[Int]("id2") }.toSet
      }
        
      // Partial statistics for redundancy and others
      total += partialCount.value      
      jointMatrix = jointMatrix match {
        case jm: CSCMatrix[Double] => jm += partialJoint.value.asInstanceOf[CSCMatrix[Double]]
        case dm: BDM[Double] => dm += partialJoint.value.asInstanceOf[BDM[Double]]
      }
      marginalVector = marginalVector match {
        case sv: BSV[Double] => sv += partialMarginal.value.asInstanceOf[BSV[Double]]
        case dv: BDV[Double] => dv += partialMarginal.value
      }
      //println("# omitted instances in this step: " + skipped)
        
      if(i > 0){
        finalWeights = finalWeights
          .join(results(i), finalWeights("id") === results(i)("id2"), "full_outer")
          .na.fill(0, Seq("id", "id2", "score", "score2"))
          .selectExpr("greatest(id,id2) as id", "score + score2 as score")
      } else {
        finalWeights = results(0).selectExpr("id2 as id", "score2 as score")
      } 
        
      // Free some resources
      bNeighborsTable.destroy(); bFullQuery.destroy(); idxModelQuery.unpersist();
      val btime = (System.currentTimeMillis - start) / 1000
      logInfo("Batch #" + i + " computed in " + btime + "s")
    }
    
    finalWeights.cache()
    val nWeights = finalWeights.count()
    val stats = finalWeights.agg(max("score"), min("score")).head()
    val maxRelief = stats.getAs[Float](0); val minRelief = stats.getAs[Float](1)
    val normalizeUDF = udf((x: Float) => (x - minRelief) / (maxRelief - minRelief), DataTypes.FloatType)
    finalWeights = finalWeights.withColumn("norm-score", normalizeUDF(col("score")))
    
    // Unpersist partial results
    (0 until batches.size).foreach(i => results(i).unpersist())
    
    // normalized redundancy
    val redundancyMatrix = computeRedudancy(jointMatrix, marginalVector, total, nFeat)
    val rddFinalWeights = finalWeights.rdd.map{ case Row(k: Int, _, normScore: Float) => (k, normScore)}.cache()
    val (reliefCol, relief) = selectFeatures(rddFinalWeights, redundancyMatrix, nFeat)
    val outRC = reliefCol.map { case F(feat, score) => (feat + 1) + "\t" + score.toString() }.mkString("\n")
    val outR = relief.map { case F(feat, score) => (feat + 1) + "\t" + score.toString() }.mkString("\n")
    println("\n*** RELIEF + Collisions selected features ***\nFeature\tScore\tRelevance\tRedundancy\n" + outRC)
    println("\n*** RELIEF selected features ***\nFeature\tScore\tRelevance\tRedundancy\n" + outR)
    
    val model = new ReliefFRSelectorModel(uid, relief.map(_.feat).toArray, reliefCol.map(_.feat).toArray)
    copyValues(model)
  }
  
  private def normalizeRankingDF(partialWeights: Dataset[_]) = {
      val scores = partialWeights.agg(min("score2"), max("score2")).head()
      val maxRelief = scores.getAs[Float](1); val minRelief = scores.getAs[Float](0)
      val normalizeUDF = udf((x: Float) => (x - minRelief) / (maxRelief - minRelief), DataTypes.FloatType)
      partialWeights.withColumn("score2", normalizeUDF(col("score2")))
  }  
  
  // TODO: Fix the MultiProbe NN Search in SPARK-18454
  private def approxNNByPartition(
      modelDataset: Dataset[_],
      bModelQuery: Broadcast[Array[Row]],
      k: Int,
      hashCol: String): RDD[(Long, Map[Int, Iterable[Int]])] = {
    
    case class Localization(part: Int, index: Int)
    val sc = modelDataset.sparkSession.sparkContext
    val qstep = $(queryStep)
    val lshOn = $(numHashTables) > 0
    val input = if(lshOn) modelDataset.select($(inputCol), hashCol) else modelDataset.select($(inputCol))
    val icol = $(inputCol)
    
    val neighbors = input.rdd.mapPartitionsWithIndex { 
      case (pindex, it) => 
          // Initialize the map composed by the priority queue and the central element's ID
          val query = bModelQuery.value // Fields: "UniqueID", $(inputCol), $(labelCol), "hashOutputCol"
          val ordering = Ordering[Float].on[(Float, Localization)](_._1).reverse// BPQ needs reverse ordering   
          val neighbors = query.map { r => r.getAs[Long]("UniqueID") -> new BoundedPriorityQueue[(Float, Localization)](k)(ordering) }   
      
          var i = 0
          // First iterate over the local elements, and then over the sampled set (also called query set).
          while(it.hasNext) {
            if(lshOn) {
              val Row(inputNeig: Vector, hashNeig: WrappedArray[Vector]) = it.next
              (0 until query.size).foreach { j => 
                 val Row(_, inputQuery: Vector, _, hashQuery: WrappedArray[Vector]) = query(j) 
                 val hdist = BucketedRandomLSH.hashThresholdedDistance(hashQuery.array, hashNeig.array, qstep)
                 if(hdist < Double.PositiveInfinity) {
                   val distance = BucketedRandomLSH.keyDistance(inputQuery, inputNeig).toFloat
                   neighbors(j)._2 += distance -> Localization(pindex.toShort, i)
                 }    
               }
            } else {
              val inputNeig = it.next.getAs[Vector](icol)
              (0 until query.size).foreach { j => 
                 val distance = BucketedRandomLSH.keyDistance(query(j).getAs[Vector](icol), inputNeig).toFloat
                 neighbors(j)._2 += distance -> Localization(pindex.toShort, i)    
               }
            }
            i += 1              
          }            
          neighbors.toIterator
      }.reduceByKey(_ ++= _).mapValues(
          _.map(l => Localization.unapply(l._2).get).groupBy(_._1).mapValues(_.map(_._2)).map(identity)) 
      // map(identity) needed to fix bug: https://issues.scala-lang.org/browse/SI-7005
    neighbors
  }
  
  object MOD {
    val sameClass = 0
    val otherClass = 1
  }
  
  private def computeReliefWeights (
      modelDataset: Dataset[_],
      bModelQuery: Broadcast[Array[Row]],
      bNeighborsTable: Broadcast[Map[Long, Map[Int, Iterable[Int]]]],
      topFeatures: Set[Int],
      priorClass: Map[Double, Float],
      nFeat: Int,
      nElems: Long
      ) = {
    
     // Initialize accumulators for RELIEF+Collision computation
    val sc = modelDataset.sparkSession.sparkContext
    val accMarginal = new VectorAccumulator(nFeat, sparse = false); sc.register(accMarginal, "marginal")
    val accJoint = new MatrixAccumulator(nFeat, nFeat, sparse = true); sc.register(accJoint, "joint")
    val totalInteractions = sc.longAccumulator("totalInteractions")
    val label2Num = priorClass.zipWithIndex.map{case (k, v) => k._1 -> v}
    val idxPriorClass = priorClass.zipWithIndex.map{case (k, v) => v -> k._2}
    val bClassCounter = new VectorAccumulator(label2Num.size * 2, sparse = false); sc.register(bClassCounter, "classCounter")
    val bTF = sc.broadcast(topFeatures)
    val isCont = !$(discreteData); val lowerDistanceTh = $(lowerDistanceThreshold); val icol = $(inputCol); val lcol = $(labelCol)
        
    val rawReliefWeights = modelDataset.rdd.mapPartitionsWithIndex { case(pindex, it) =>
        // last position is reserved to negative weights from central instances.
        val localExamples = it.toArray
        val marginal = BDV.zeros[Double](nFeat)        
        val joint = new CSCMatrix.Builder[Double](rows = nFeat, cols = nFeat)
        val reliefWeights = BDV.fill[BDV[Float]](nFeat)(BDV.zeros[Float](label2Num.size * 2)) 
        val classCounter = BDV.zeros[Double](label2Num.size * 2)        
        val r = new scala.util.Random($(seed))
        // Data are assumed to be scaled to have 0 mean, and 1 std
        val vote = if(isCont) (d: Double) => 1 - math.min(6.0, d) / 6.0 else (d: Double) => Double.MinPositiveValue
        val mainCollisioned = Queue[Int](); val auxCollisioned = Queue[Int]() // annotate similar features
        val pcounter = Array.fill(nFeat)(0.0d) // isolate the strength of collision by feature
        val jointVote = if(isCont) (i1: Int, i2: Int) => (pcounter(i1) + pcounter(i2)) / 2 else 
                      (i1: Int, _: Int) => pcounter(i1)
                      
        bModelQuery.value.map{ row =>
            val qid = row.getAs[Long]("UniqueID"); val qinput = row.getAs[Vector](icol); val qlabel = row.getAs[Double](lcol)
            bNeighborsTable.value.get(qid) match { 
              case Some(localMap) =>
                localMap.get(pindex) match {
                  case Some(neighbors) =>
                    val rnumber = r.nextFloat()
                    val distanceThreshold = if(isCont) 6 * (1 - (lowerDistanceTh + rnumber * lowerDistanceTh)) else 0.0f
                    neighbors.map{ lidx =>
                      val Row(ninput: Vector, nlabel: Double) = localExamples(lidx)
                      val labelIndex = label2Num.get(nlabel.toFloat).get
                      val mod = if(nlabel == qlabel) label2Num.size * MOD.sameClass else label2Num.size * MOD.otherClass
                      classCounter(labelIndex + mod) += 1
                            
                      qinput.foreachActive{ (index, value) =>
                         val fdistance = math.abs(value - ninput(index))
                         //// RELIEF Computations
                         reliefWeights(index)(labelIndex + mod) += fdistance.toFloat
                         //// Check if there exist a collision
                         // The closer the distance, the more probable.
                         if(fdistance <= distanceThreshold){
                            val contribution = vote(fdistance)
                            marginal(index) += contribution
                            pcounter(index) = contribution
                            val fit = mainCollisioned.iterator
                            while(fit.hasNext){
                              val i2 = fit.next
                              joint.add(i2, index, jointVote(index, i2))
                            } 
                            // Check if redundancy is relevant here. Depends on the feature' score in the previous stage.
                            if(bTF.value.contains(index)){ 
                              // Relevant, the feature is added to the main group and update auxiliar feat's.
                              mainCollisioned += index
                              val fit = auxCollisioned.iterator
                              while(fit.hasNext){
                                val i2 = fit.next
                                joint.add(i2, index, jointVote(index, i2))
                              }
                            } else { // Irrelevant, added to the secondary group
                              auxCollisioned += index
                            }
                         }
                        }                        
                        mainCollisioned.clear(); auxCollisioned.clear()
                    }
                  case None => /* Do nothing */
                }                
              case None =>
                System.err.println("Instance does not found in the table")
            }
      }
      // update accumulated matrices 
      accMarginal.add(marginal)
      accJoint.add(joint.result)
      totalInteractions.add(classCounter.sum.toLong)
      bClassCounter.add(classCounter)
      reliefWeights.activeIterator
    }.reduceByKey(_ + _)
     
    val cc = bClassCounter.value
    val weights = rawReliefWeights.mapValues { case reliefByClass =>
       val nClasses = idxPriorClass.size
       var sum = 0.0f
       reliefByClass.iterator.foreach{ case (classMod, value) =>
           val contrib = if(cc(classMod) > 0) {
             val sign = if(classMod / nClasses == MOD.sameClass) -1 else 1
             sign * idxPriorClass.get(classMod % nClasses).get * (value / cc(classMod).toFloat)
           } else {
             0.0f
           }
           sum += contrib
       }
       sum
    }
         
    (weights, accJoint, accMarginal, totalInteractions)
  }
  
  private def computeReliefWeightsSparse (
      modelDataset: Dataset[_],
      bModelQuery: Broadcast[Array[Row]],
      bNeighborsTable: Broadcast[Map[Long, Map[Int, Iterable[Int]]]],
      topFeatures: Set[Int],
      priorClass: Map[Double, Float],
      nFeat: Int,
      nElems: Long
      ) = {
    
         // Initialize accumulators for RELIEF+Collision computation
      val sc = modelDataset.sparkSession.sparkContext
      val accMarginal = new VectorAccumulator(nFeat, sparse = true); sc.register(accMarginal, "marginal")
      val cooJoint = new COOAccumulator(); sc.register(cooJoint, "coojoint")
      val totalInteractions = sc.longAccumulator("totalInteractions")
      val label2Num = priorClass.zipWithIndex.map{case (k, v) => k._1 -> v}
      val idxPriorClass = priorClass.zipWithIndex.map{case (k, v) => v -> k._2}
      val bClassCounter = new VectorAccumulator(label2Num.size * 2, sparse = false); sc.register(bClassCounter, "classCounter")
      val bTF = sc.broadcast(topFeatures)
      val isCont = !$(discreteData); val lowerDistanceTh = $(lowerDistanceThreshold); val icol = $(inputCol); val lcol = $(labelCol)
      val lseed = $(seed)
          
      val rawReliefWeights = modelDataset.rdd.mapPartitionsWithIndex { case(pindex, it) =>
          // last position is reserved to negative weights from central instances.
          val localExamples = it.toArray
          val marginal = new VectorBuilder[Double](nFeat)
          val joint = new ArrayBuffer[(Int, Int, Double)]
          val reliefWeights = new HashMap[Int, BDV[Float]]
          val classCounter = BDV.zeros[Double](label2Num.size * 2)        
          val r = new scala.util.Random(lseed)
          // Data are assumed to be scaled to have 0 mean, and 1 std
          val vote = if(isCont) (d: Double) => 1 - math.min(6.0, d) / 6.0 else (d: Double) => Double.MinPositiveValue
          val mainCollisioned = Queue[Int](); val auxCollisioned = Queue[Int]() // annotate similar features
          //val pcounter = Array.fill(nFeat)(0.0d) // isolate the strength of collision by feature
          //val jointVote = if(isCont) (i1: Int, i2: Int) => (pcounter(i1) + pcounter(i2)) / 2 else (i1: Int, _: Int) => pcounter(i1)
          val jointVote = 1.0              
          bModelQuery.value.map{ row =>
              val qid = row.getAs[Long]("UniqueID"); val qinput = row.getAs[SparseVector](icol) 
              val qlabel = row.getAs[Double](lcol)
              bNeighborsTable.value.get(qid) match { 
                case Some(localMap) =>
                  localMap.get(pindex) match {
                    case Some(neighbors) =>
                      val rnumber = r.nextFloat()
                      val distanceThreshold = if(isCont) 6 * (1 - (lowerDistanceTh + rnumber * lowerDistanceTh)) else 0.0f
                      neighbors.map{ lidx =>
                        val Row(ninput: SparseVector, nlabel: Double) = localExamples(lidx)
                        val labelIndex = label2Num.get(nlabel.toFloat).get
                        val mod = if(nlabel == qlabel) label2Num.size * MOD.sameClass else label2Num.size * MOD.otherClass
                        classCounter(labelIndex + mod) += 1
    
                        val v1Indices = qinput.indices; val nnzv1 = v1Indices.length; var kv1 = 0
                        val v2Indices = ninput.indices; val nnzv2 = v2Indices.length; var kv2 = 0
                        
                        while (kv1 < nnzv1 || kv2 < nnzv2) {
                          var score = 0.0; var index = kv1
                
                          if (kv2 >= nnzv2 || (kv1 < nnzv1 && v1Indices(kv1) < v2Indices(kv2))) {
                            score = qinput.values(kv1); kv1 += 1; index = kv1
                          } else if (kv1 >= nnzv1 || (kv2 < nnzv2 && v2Indices(kv2) < v1Indices(kv1))) {
                            score = ninput.values(kv2); kv2 += 1; index = kv2
                          } else {
                            score = qinput.values(kv1) - ninput.values(kv2); kv1 += 1; kv2 += 1; index = kv1
                          }
                          val fdistance = math.abs(score)
                          //// RELIEF Computations
                          val updateV = reliefWeights.getOrElse(index, BDV.zeros[Float](label2Num.size * 2))
                          updateV(labelIndex + mod) += fdistance.toFloat
                          reliefWeights += index -> updateV
                          //// Check if there exist a collision
                          // The closer the distance, the more probable.
                           if(fdistance <= distanceThreshold){
                              val contribution = vote(fdistance)
                              marginal.add(index, contribution)
                              //pcounter(index) = contribution
                              val fit = mainCollisioned.iterator
                              while(fit.hasNext){
                                val i2 = fit.next
                                joint += ((i2, index, jointVote))
                              } 
                              // Check if redundancy is relevant here. Depends on the feature' score in the previous stage.
                              if(bTF.value.contains(index)){ // Relevant, the feature is added to the main group and update auxiliar feat's.
                                mainCollisioned += index
                                val fit = auxCollisioned.iterator
                                while(fit.hasNext){
                                  val i2 = fit.next
                                  joint += ((i2, index, jointVote))
                                }
                              } else { // Irrelevant, added to the secondary group
                                auxCollisioned += index
                              }
                           }                          
                        }
                        mainCollisioned.clear(); auxCollisioned.clear()
                  }
                case None => /* Do nothing */
              }
              case None =>
                  System.err.println("Instance does not found in the table")
            }
        }
        // update accumulated matrices  
        accMarginal.add(marginal.toSparseVector)        
        cooJoint.add(joint)
        totalInteractions.add(classCounter.sum.toLong)
        bClassCounter.add(classCounter)
        reliefWeights.iterator
        
      }.reduceByKey(_ + _)
      
      val cc = bClassCounter.value
      val weights = rawReliefWeights.mapValues { case reliefByClass =>
         val nClasses = idxPriorClass.size
         var sum = 0.0f
         reliefByClass.iterator.foreach{ case (classMod, value) =>
             val contrib = if(cc(classMod) > 0) {
               val sign = if(classMod / nClasses == MOD.sameClass) -1 else 1
               sign * idxPriorClass.get(classMod % nClasses).get * (value / cc(classMod).toFloat)
             } else {
               0.0f
             }
             sum += contrib
         }
         sum
      }
      
      val accJoint = new MatrixAccumulator(nFeat, nFeat, cooJoint.value); sc.register(accJoint, "joint")
      (weights, accJoint, accMarginal, totalInteractions)
  }
  
  private def computeRedudancy(rawJoint: BM[Double], rawMarginal: BV[Double], 
      total: Long, nFeat: Int) = {
    // Now compute redundancy based on collisions and normalize it
    val factor = if($(discreteData)) Double.MinPositiveValue else 1.0
    val marginal = rawMarginal.mapActiveValues(_ /  (total * factor))
    
    var maxRed = Double.NegativeInfinity; var minRed = Double.PositiveInfinity
    val jointTotal = total * factor * (1 - $(estimationRatio) * $(batchSize)) // we omit the first batch
    // Apply the factor and the entropy formula
    val applyEntropy = (i1: Int, i2: Int, value: Double) => {
      val jprob = value / jointTotal
      val red = jprob * log2(jprob / (marginal(i1) * marginal(i2)))  
      val correctedRed = if(!red.isNaN()) red else 0
      if(correctedRed > maxRed) {
        maxRed = correctedRed 
      } else if(correctedRed < minRed) {
        minRed = correctedRed
      }
      correctedRed
    }
    // Compute the final redundancy matrix with both diagonals to allow direct selection by column id
    rawJoint match {
      case bsm: CSCMatrix[Double] => 
        val upperDiagonal = bsm.copy
        val lowerDiagonal = new CSCMatrix.Builder[Double](rows = nFeat, cols = nFeat)
        bsm.activeIterator.foreach{case((i1,i2), value) => 
          val ent = applyEntropy(i1, i2, value)
          upperDiagonal(i1, i2) = ent
          lowerDiagonal.add(i2, i1, ent)
        }
        (upperDiagonal + lowerDiagonal.result).mapActiveValues { e => (e - minRed) / (maxRed - minRed) }
      case bdm: BDM[Double] => 
        val redm = bdm.copy
        bdm.foreachPair{ case((i1,i2), value) => 
          if(i1 < i2) { // We just fulfilled the upper diagonal
            val ent = applyEntropy(i1, i2, value)
            redm(i1, i2) = ent; redm(i2, i1) = ent
          }
        }
        redm.mapValues { e => (e - minRed) / (maxRed - minRed) }   
    }
  }

  // Case class for criteria/feature
  case class F(feat: Int, crit: FeatureScore)
  
  def selectFeatures(reliefRanking: RDD[(Int, Float)],
      redundancyMatrix: BM[Double], nFeat: Int): (Seq[F], Seq[F]) = {
    
    // Initialize all (except the class) criteria with the relevance values
    val reliefNoColl = reliefRanking.takeOrdered($(numTopFeatures))(Ordering[(Double, Int)].on(f => (-f._2, f._1)))
      .map{case (feat, crit) => F(feat, new FeatureScore().init(crit.toFloat))}.toSeq
    val actualWeights = reliefRanking.mapValues{ score => new FeatureScore().init(score.toFloat)}.sortBy(_._1).collect()
    val pool: Array[Option[FeatureScore]] = Array.fill(nFeat)(None)
    actualWeights.foreach{case (id, score) => pool(id) = Some(score)}
    // Get the maximum and initialize the set of selected features with it
    var selected = Seq(reliefNoColl.head)
    pool(reliefNoColl.head.feat).get.valid = false
    var moreFeat = true
    
    // Iterative process for redundancy and conditional redundancy
    while (selected.size < $(numTopFeatures) && moreFeat) {

      // Update criteria with the new redundancy values      
      redundancyMatrix match{
        case bsm: CSCMatrix[Double] => 
          val begin = bsm.colPtrs(selected.head.feat)
          val end = bsm.colPtrs(selected.head.feat + 1)
          (begin until end).foreach{ k =>
            val feat = bsm.rowIndices(k)
            if(pool(feat).get.valid){
              pool(feat).get.update(bsm.data(k).toFloat)
            }              
          }
        case bdm: BDM[Double] =>
          (0 until pool.size).foreach{ k =>  
            if(pool(k).get.valid)
              pool(k).get.update(bdm(k, selected.head.feat).toFloat)
          }
      }
      
      // select the best feature and remove from the whole set of features
      var max = new FeatureScore().init(Float.NegativeInfinity); var maxi = -1
      val maxscore = max.score
      (0 until pool.size).foreach{ i => 
        pool(i) match {
          case Some(crit) => 
            if(crit.valid && crit.score > max.score){
              maxi = i; max = crit
            } 
          case None => /* Do nothing */
        }
      }
      
      if(maxi > -1){
        selected = F(maxi, max) +: selected
        max.valid = false
      } else {
        moreFeat = false
      }
    }
    (selected.reverse, reliefNoColl)  
  }
  
  class FeatureScore extends Serializable {
    var relevance: Float = 0.0f
    var redundance: Float = 0.0f
    var selectedSize: Int = 0
    var valid = true
  
    def score = {
      if (selectedSize > 0) {
        relevance - redundance / selectedSize
      } else {
        relevance
      }
    }
    override def toString() = {
      "%.4f".format(score) + "\t" +
      "%.4f".format(relevance) + "\t" +
      "%.4f".format(redundance)
    }
    def init(relevance: Float): FeatureScore = {
      this.relevance = relevance
      this
    }
    
    def update(mi: Float): FeatureScore = {
      redundance += mi
      selectedSize += 1
      this
    }
  } 
   
  private def log2(x: Double) = { math.log(x) / math.log(2) }

  override def copy(extra: ParamMap): ReliefFRSelector = defaultCopy(extra)
}

@Since("1.6.0")
object ReliefFRSelector extends DefaultParamsReadable[ReliefFRSelector] {

  @Since("1.6.0")
  override def load(path: String): ReliefFRSelector = super.load(path)
}

/**
 * :: Experimental ::
 * Model fitted by [[ReliefFRSelector]].
 */
@Experimental
final class ReliefFRSelectorModel private[ml] (
  @Since("1.6.0") override val uid: String,
  private val reliefFeatures: Array[Int],
  private val reliefColFeatures: Array[Int]
)
    extends Model[ReliefFRSelectorModel] with ReliefFRSelectorParams with MLWritable {

  import ReliefFRSelectorModel._
  
  def setOutputCol(value: String): this.type = set(outputCol, value)
  def setInputCol(value: String): this.type = set(inputCol, value)
  def setRedundancyRemoval(value: Boolean): this.type = set(redundancyRemoval, value)
  
  private var subset: Int = getSelectedFeatures().length
  def setReducedSubset(s: Int): this.type =  {
    if(s > 0 && s <= getSelectedFeatures().length){
      subset = s
    } else {
      System.err.println("The number of features in the subset must" +
        " be lower than the total number and greater than 0")
    }
    this
  }
  
  def getReducedSubsetParam(): Int = subset
  def getSelectedFeatures(): Array[Int] = if($(redundancyRemoval)) reliefFeatures else reliefColFeatures

  override def transform(dataset: Dataset[_]): DataFrame = {
    val transformedSchema = transformSchema(dataset.schema, logging = true)
    val newField = transformedSchema.last

    // TODO: Make the transformer natively in ml framework to avoid extra conversion.
    val sfeat = getSelectedFeatures().slice(0, subset)
    val transformer: Vector => Vector = v =>  FeatureSelectionUtils.compress(OldVectors.fromML(v), sfeat).asML
    val selector = udf(transformer)

    dataset.withColumn($(outputCol), selector(col($(inputCol))), newField.metadata)
  }

  override def transformSchema(schema: StructType): StructType = {
    SchemaUtils.checkColumnType(schema, $(inputCol), new VectorUDT)
    val newField = prepOutputField(schema)
    val outputFields = schema.fields :+ newField
    StructType(outputFields)
  }

  /**
   * Prepare the output column field, including per-feature metadata.
   */
  private def prepOutputField(schema: StructType): StructField = {
    val origAttrGroup = AttributeGroup.fromStructField(schema($(inputCol)))
    val featureAttributes: Array[Attribute] = if (origAttrGroup.attributes.nonEmpty) {
      origAttrGroup.attributes.get.zipWithIndex.filter(x => reliefFeatures.contains(x._2)).map(_._1)
    } else {
      Array.fill[Attribute](reliefFeatures.size)(NumericAttribute.defaultAttr)
    }
    val newAttributeGroup = new AttributeGroup($(outputCol), featureAttributes)
    newAttributeGroup.toStructField()
  }

  override def copy(extra: ParamMap): ReliefFRSelectorModel = {
    val copied = new ReliefFRSelectorModel(uid, reliefFeatures, reliefColFeatures)
    copyValues(copied, extra).setParent(parent)
  }

  @Since("1.6.0")
  override def write: MLWriter = new ReliefFRSelectorModelWriter(this)
}

@Since("1.6.0")
object ReliefFRSelectorModel extends MLReadable[ReliefFRSelectorModel] {

  private[ReliefFRSelectorModel] class ReliefFRSelectorModelWriter(instance: ReliefFRSelectorModel) extends MLWriter {

    private case class Data(reliefFeatures: Seq[Int], reliefColFeatures: Seq[Int])

    override protected def saveImpl(path: String): Unit = {
      DefaultParamsWriter.saveMetadata(instance, path, sc)
      val data = Data(instance.reliefFeatures.toSeq, instance.reliefColFeatures.toSeq)
      val dataPath = new Path(path, "data").toString
      sparkSession.createDataFrame(Seq(data)).repartition(1).write.parquet(dataPath)
    }
  }

  private class ReliefFRSelectorModelReader extends MLReader[ReliefFRSelectorModel] {

    private val className = classOf[ReliefFRSelectorModel].getName

    override def load(path: String): ReliefFRSelectorModel = {
      val metadata = DefaultParamsReader.loadMetadata(path, sc, className)
      val dataPath = new Path(path, "data").toString
      val data = sparkSession.read.parquet(dataPath).select("reliefFeatures", "reliefColFeatures").head()
      val reliefFeatures = data.getAs[Seq[Int]](0).toArray
      val reliefColFeatures = data.getAs[Seq[Int]](1).toArray
      val model = new ReliefFRSelectorModel(metadata.uid, reliefFeatures, reliefColFeatures)
      DefaultParamsReader.getAndSetParams(model, metadata)
      model
    }
  }

  @Since("1.6.0")
  override def read: MLReader[ReliefFRSelectorModel] = new ReliefFRSelectorModelReader

  @Since("1.6.0")
  override def load(path: String): ReliefFRSelectorModel = super.load(path)
}
