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

package org.graphframes.lib

import java.io.IOException
import java.math.BigDecimal
import java.util.UUID

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.DecimalType
import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.storage.StorageLevel

import org.graphframes.{GraphFrame, Logging}

/**
 * Connected components algorithm.
 *
 * Computes the connected component membership of each vertex and returns a DataFrame of vertex
 * information with each vertex assigned a component ID.
 *
 * The resulting DataFrame contains all the vertex information and one additional column:
 *  - component (`LongType`): unique ID for this component
 */
class ConnectedComponents private[graphframes] (
    private val graph: GraphFrame) extends Arguments with Logging {

  import org.graphframes.lib.ConnectedComponents._

  private var broadcastThreshold: Int = 1000000

  /**
   * Sets broadcast threshold in propagating component assignments (default: 1000000).
   * If a node degree is greater than this threshold at some iteration, its component assignment
   * will be collected and then broadcasted back to propagate the assignment to its neighbors.
   * Otherwise, the assignment propagation is done by a normal Spark join.
   * This parameter is only used when the algorithm is set to "graphframes".
   */
  def setBroadcastThreshold(value: Int): this.type = {
    require(value >= 0, s"Broadcast threshold must be non-negative but got $value.")
    broadcastThreshold = value
    this
  }

  // python-friendly setter
  private[graphframes] def setBroadcastThreshold(value: java.lang.Integer): this.type = {
    setBroadcastThreshold(value.toInt)
  }

  /**
   * Gets broadcast threshold in propagating component assignment.
   * @see [[org.graphframes.lib.ConnectedComponents.setBroadcastThreshold]]
   */
  def getBroadcastThreshold: Int = broadcastThreshold

  private var algorithm: String = ALGO_GRAPHFRAMES

  /**
   * Sets the connected components algorithm to use (default: "graphframes").
   * Supported algorithms are:
   *   - "graphframes": Uses alternating large star and small star iterations proposed in
   *     [[http://dx.doi.org/10.1145/2670979.2670997 Connected Components in MapReduce and Beyond]]
   *     with skewed join optimization.
   *   - "graphx": Converts the graph to a GraphX graph and then uses the connected components
   *     implementation in GraphX.
   * @see [[org.graphframes.lib.ConnectedComponents.supportedAlgorithms]]
   */
  def setAlgorithm(value: String): this.type = {
    require(supportedAlgorithms.contains(value),
      s"Supported algorithms are {${supportedAlgorithms.mkString(", ")}}, but got $value.")
    algorithm = value
    this
  }

  /**
   * Gets the connected component algorithm to use.
   * @see [[org.graphframes.lib.ConnectedComponents.setAlgorithm]].
   */
  def getAlgorithm: String = algorithm

  private var checkpointInterval: Int = 2

  private var pruneNodeIter: Int = 1

  private var sparsityThreshold: Double = 2

  private var maxAttempts: Int = 3

  private var shrinkageThreshold: Double = 5


  /**
   * Sets checkpoint interval in terms of number of iterations (default: 2).
   * Checkpointing regularly helps recover from failures, clean shuffle files, shorten the
   * lineage of the computation graph, and reduce the complexity of plan optimization.
   * As of Spark 2.0, the complexity of plan optimization would grow exponentially without
   * checkpointing.
   * Hence disabling or setting longer-than-default checkpoint intervals are not recommended.
   * Checkpoint data is saved under `org.apache.spark.SparkContext.getCheckpointDir` with
   * prefix "connected-components".
   * If the checkpoint directory is not set, this throws a `java.io.IOException`.
   * Set a nonpositive value to disable checkpointing.
   * This parameter is only used when the algorithm is set to "graphframes".
   * Its default value might change in the future.
   * @see `org.apache.spark.SparkContext.setCheckpointDir` in Spark API doc
   */
  def setCheckpointInterval(value: Int): this.type = {
    if (value <= 0 || value > 2) {
      logger.warn(
        s"Set checkpointInterval to $value. This would blow up the query plan and hang the " +
          "driver for large graphs.")
    }
    checkpointInterval = value
    this
  }

  // python-friendly setter
  private[graphframes] def setCheckpointInterval(value: java.lang.Integer): this.type = {
    setCheckpointInterval(value.toInt)
  }

  /**
   * Gets checkpoint interval.
   * @see [[org.graphframes.lib.ConnectedComponents.setCheckpointInterval]]
   */
  def getCheckpointInterval: Int = checkpointInterval


  def setPruneNodeIter(value: Int): this.type = {
    if (value <= 0 || value > 2) {
      logger.warn(
        s"Set pruneNodeIter to $value. This would blow up the query plan and hang the " +
          "driver for large graphs.")
    }
    pruneNodeIter = value
    this
  }

  // python-friendly setter
  private[graphframes] def setPruneNodeIter(value: java.lang.Integer): this.type = {
    setPruneNodeIter(value.toInt)
  }

  /**
   * Gets checkpoint interval.
   * @see [[org.graphframes.lib.ConnectedComponents.setCheckpointInterval]]
   */
  def getPruneNodeIter: Int = pruneNodeIter



  private var intermediateStorageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK

  /**
   * Sets storage level for intermediate datasets that require multiple passes (default: ``MEMORY_AND_DISK``).
   */
  def setIntermediateStorageLevel(value: StorageLevel): this.type = {
    intermediateStorageLevel = value
    this
  }

  /**
   * Gets storage level for intermediate datasets that require multiple passes.
   */
  def getIntermediateStorageLevel: StorageLevel = intermediateStorageLevel

  /**
   * Runs the algorithm.
   */
  def run(): DataFrame = {
    ConnectedComponents.run(graph,
      algorithm = algorithm,
      broadcastThreshold = broadcastThreshold,
      checkpointInterval = checkpointInterval,
      intermediateStorageLevel = intermediateStorageLevel,
      pruneNodeIter = pruneNodeIter,
      sparsityThreshold = sparsityThreshold,
      shrinkageThreshold = shrinkageThreshold,
      maxAttempts = maxAttempts)
  }
}

object ConnectedComponents extends Logging {

  import org.graphframes.GraphFrame._

  private val COMPONENT = "component"
  private val ORIG_ID = "orig_id"
  private val MIN_NBR = "min_nbr"
  private val CNT = "cnt"
  private val CHECKPOINT_NAME_PREFIX = "connected-components"

  private val ALGO_GRAPHX = "graphx"
  private val ALGO_GRAPHFRAMES = "graphframes"

  /**
   * Supported algorithms in [[org.graphframes.lib.ConnectedComponents.setAlgorithm]]: "graphframes"
   * and "graphx".
   */
  val supportedAlgorithms: Array[String] = Array(ALGO_GRAPHX, ALGO_GRAPHFRAMES)

  /**
   * Returns the symmetric directed graph of the graph specified by input edges.
   * @param ee non-bidirectional edges
   */
  private def symmetrize(ee: DataFrame): DataFrame = {
    val EDGE = "_edge"
    ee.select(explode(array(
          struct(col(SRC), col(DST)),
          struct(col(DST).as(SRC), col(SRC).as(DST)))
        ).as(EDGE))
      .select(col(s"$EDGE.$SRC").as(SRC), col(s"$EDGE.$DST").as(DST))
  }

  /**
   * Prepares the input graph for computing connected components by:
   *   - de-duplicating vertices and assigning unique long IDs to each,
   *   - changing edge directions to have increasing long IDs from src to dst,
   *   - de-duplicating edges and removing self-loops.
   * In the returned GraphFrame, the vertex DataFrame has two columns:
   *   - column `id` stores a long ID assigned to the vertex,
   *   - column `attr` stores the original vertex attributes.
   * The edge DataFrame has two columns:
   *   - column `src` stores the long ID of the source vertex,
   *   - column `dst` stores the long ID of the destination vertex,
   * where we always have `src` < `dst`.
   */
  private def prepare(graph: GraphFrame): GraphFrame = {
    // TODO: This assignment job might fail if the graph is skewed.
    val vertices = graph.indexedVertices
      .select(col(LONG_ID).as(ID), col(ATTR))
      // TODO: confirm the contract for a graph and decide whether we need distinct here
      // .distinct()
    val edges = graph.indexedEdges
      .select(col(LONG_SRC).as(SRC), col(LONG_DST).as(DST))
    val orderedEdges = edges.filter(col(SRC) !== col(DST))
      .select(minValue(col(SRC), col(DST)).as(SRC), maxValue(col(SRC), col(DST)).as(DST))
      .distinct()
    GraphFrame(vertices, orderedEdges)
  }

  /**
   * Returns the min vertex among each vertex and its neighbors in a DataFrame with three columns:
   *   - `src`, the ID of the vertex
   *   - `min_nbr`, the min vertex ID among itself and its neighbors
   *   - `cnt`, the total number of neighbors
   */
  private def minNbrs(ee: DataFrame): DataFrame = {
    symmetrize(ee)
      .groupBy(SRC).agg(min(col(DST)).as(MIN_NBR), count("*").as(CNT))
      .withColumn(MIN_NBR, minValue(col(SRC), col(MIN_NBR)))
  }

  private def minValue(x: Column, y: Column): Column = {
    when(x < y, x).otherwise(y)
  }

  private def maxValue(x: Column, y: Column): Column = {
    when(x > y, x).otherwise(y)
  }

  /**
   * Performs a possibly skewed join between edges and current component assignments.
   * The skew join is done by broadcast join for frequent keys and normal join for the rest.
   */
  private def skewedJoin(
      edges: DataFrame,
      minNbrs: DataFrame,
      broadcastThreshold: Int,
      logPrefix: String): DataFrame = {
    import edges.sqlContext.implicits._
    val hubs = minNbrs.filter(col(CNT) > broadcastThreshold)
      .select(SRC)
      .as[Long]
      .collect()
      .toSet
    GraphFrame.skewedJoin(edges, minNbrs, SRC, hubs, logPrefix)
  }



  private def pruneLeafNodes(
    edges: DataFrame,
    intermediateStorageLevel: StorageLevel) = {

    // vertices whose indegree > 1.
    val v1 = edges.groupBy(DST).agg(count("*").as("count"))
      .filter(col("count") > 1).select(DST)
    //  .persist(intermediateStorageLevel) 
       
    // vertices whose outdegree > 0 or indegree > 1.
    val new_vv = edges.select(SRC).union(v1).distinct().withColumnRenamed(SRC, ID)
      .persist(intermediateStorageLevel)

    val new_ee = edges.join(new_vv.withColumnRenamed(ID, DST), DST)
    (new_vv, new_ee)
  }

  // keep source nodes in the edge set, prune other nodes.
  private def keepSrcNodes(
    edges: DataFrame,
    intermediateStorageLevel: StorageLevel) = {
    val TMP = "tmp"  // temporary column name
    var new_vv = edges.select(col(SRC)).distinct()
        .persist(intermediateStorageLevel)

    val je = edges.union(new_vv.withColumn(DST, col(SRC))) 
    
    // edges set of the small graph
    val new_ee = je.join(je.withColumnRenamed(SRC, TMP), DST)
          .select(SRC, TMP).withColumnRenamed(TMP, DST)
          .filter(col(SRC) < col(DST))
          .distinct() // src < dst
    new_vv = new_vv.withColumnRenamed(SRC, ID)
    (new_vv, new_ee)
  }

  /**
   * Runs connected components with default parameters.
   */
  def run(graph: GraphFrame): DataFrame = {
    new ConnectedComponents(graph).run()
  }

  private def runGraphX(graph: GraphFrame): DataFrame = {
    val components = org.apache.spark.graphx.lib.ConnectedComponents.run(graph.cachedTopologyGraphX)
    GraphXConversions.fromGraphX(graph, components, vertexNames = Seq(COMPONENT)).vertices
  }

  private def run(
      graph: GraphFrame,
      algorithm: String,
      broadcastThreshold: Int,
      checkpointInterval: Int,
      intermediateStorageLevel: StorageLevel,
      pruneNodeIter: Int,
      sparsityThreshold: Double,
      shrinkageThreshold: Double,
      maxAttempts: Int): DataFrame = {
    require(supportedAlgorithms.contains(algorithm),
      s"Supported algorithms are {${supportedAlgorithms.mkString(", ")}}, but got $algorithm.")

    if (algorithm == ALGO_GRAPHX) {
      return runGraphX(graph)
    }

    val runId = UUID.randomUUID().toString.takeRight(8)
    val logPrefix = s"[CC $runId]"
    logger.info(s"$logPrefix Start connected components with run ID $runId.")

    val sqlContext = graph.sqlContext
    val sc = sqlContext.sparkContext
    val TMP = "tmp"  // temporary column name


    val shouldCheckpoint = checkpointInterval > 0
    val checkpointDir: Option[String] = if (shouldCheckpoint) {
      val dir = sc.getCheckpointDir.map { d =>
        new Path(d, s"$CHECKPOINT_NAME_PREFIX-$runId").toString
      }.getOrElse {
        throw new IOException(
          "Checkpoint directory is not set. Please set it first using sc.setCheckpointDir().")
      }
      logger.info(s"$logPrefix Using $dir for checkpointing with interval $checkpointInterval.")
      Some(dir)
    } else {
      logger.info(
        s"$logPrefix Checkpointing is disabled because checkpointInterval=$checkpointInterval.")
      None
    }

    logger.info(s"$logPrefix Preparing the graph for connected component computation ...")
    val g = prepare(graph)
    var vv = g.vertices.persist(intermediateStorageLevel)
    var ee = g.edges.persist(intermediateStorageLevel) // src < dst
    var attempt = 1
    var isOptimized = false

    var numEdges = ee.count()
    var numNodes = vv.count()
    logger.info(s"$logPrefix Found $numNodes nodes after preparation.")
    logger.info(s"$logPrefix Found $numEdges edges after preparation.")

    var converged = false
    var iteration = 1
    var prevSum: BigDecimal = null
    var old_ee: DataFrame = null
    var old_vv: DataFrame = vv

    while (!converged) {
      // large-star step
      // compute min neighbors (including self-min)
      val minNbrs1 = minNbrs(ee) // src >= min_nbr
        .persist(intermediateStorageLevel)
      // connect all strictly larger neighbors to the min neighbor (including self)
      val ee1 = skewedJoin(ee, minNbrs1, broadcastThreshold, logPrefix)
        .select(col(DST).as(SRC), col(MIN_NBR).as(DST)) // src > dst
        .distinct()
        .persist(intermediateStorageLevel)


      // small-star step
      // compute min neighbors (excluding self-min)
      val minNbrs2 = ee1.groupBy(col(SRC)).agg(min(col(DST)).as(MIN_NBR), count("*").as(CNT)) // src > min_nbr
        .persist(intermediateStorageLevel)
      // connect all smaller neighbors to the min neighbor
      ee = skewedJoin(ee1, minNbrs2, broadcastThreshold, logPrefix)
        .select(col(MIN_NBR).as(SRC), col(DST)) // src <= dst
        .filter(col(SRC) =!= col(DST)) // src < dst
      // connect self to the min neighbor
      ee = ee.union(minNbrs2.select(col(MIN_NBR).as(SRC), col(SRC).as(DST))) // src < dst
        .distinct()

      // checkpointing
      if (shouldCheckpoint && (iteration % checkpointInterval == 0)) {
        // TODO: remove this after DataFrame.checkpoint is implemented
        val out = s"${checkpointDir.get}/$iteration"
        ee.write.parquet(out)
        // may hit S3 eventually consistent issue
        ee = sqlContext.read.parquet(out)

        minNbrs1.unpersist(false)
        minNbrs2.unpersist(false)
        ee1.unpersist(false)

        // remove previous checkpoint
        if (iteration > checkpointInterval) {
          val path = new Path(s"${checkpointDir.get}/${iteration - checkpointInterval}")
          path.getFileSystem(sc.hadoopConfiguration).delete(path, true)
        }

        System.gc() // hint Spark to clean shuffle directories
      }


      // test convergence

      // Taking the sum in DecimalType to preserve precision.
      // We use 20 digits for long values and Spark SQL will add 10 digits for the sum.
      // It should be able to handle 200 billion edges without overflow.
      val (currSum, edgeCnt) = ee.select(sum(col(SRC).cast(DecimalType(20, 0))), count("*")).rdd
        .map { r =>
          (r.getAs[BigDecimal](0), r.getLong(1))
        }.first()

      if (edgeCnt != 0L && currSum == null) {
        throw new ArithmeticException(
          s"""
             |The total sum of edge src IDs is used to determine convergence during iterations.
             |However, the total sum at iteration $iteration exceeded 30 digits (1e30),
             |which should happen only if the graph contains more than 200 billion edges.
             |If not, please file a bug report at https://github.com/graphframes/graphframes/issues.
            """.stripMargin)
      }
      
      // pruning node optimization: construct a new small graph with few nodes, 
      // find connected components of the small graph, then join back to get the 
      // connected components of the original graph. 

      // When edges / nodes < 2 (i.e. sparse graph), perform pruning node optimization.
      // sparse, 5 >= iter >= 1, |new_v| * 5 <= |v| 
  
      ee.persist(intermediateStorageLevel)


      if(iteration == pruneNodeIter) 
      {
        
        old_ee = ee
 
        // Pruning Leaf Nodes
        val r = pruneLeafNodes(ee, intermediateStorageLevel)
        
        // Keep Source Nodes, prune other nodes
        //val r = keepSrcNodes(ee, intermediateStorageLevel)

        new_vv = r._1
        ee = r._2

        // number of nodes in the shrinked graph
        numNodes = new_vv.count()
        isOptimized = true
        ee.persist(intermediateStorageLevel)

      }

    

/*
      if(edgeCnt < sparsityThreshold * numNodes && isOptimized == false) // sparse graph condition 
      {
        //if(iteration == pruneNodeIter)  Remove pruneNodeIter

        // if a graph does not have edges, do not perform pruning node optimization
        if(edgeCnt == 0) 
          attempt = maxAttempts + 1
        
        if(attempt <= maxAttempts)
        {
          ee.persist(intermediateStorageLevel)

          // vertices set of the small graph
          new_vv = ee.select(col(SRC)).distinct()
            .persist(intermediateStorageLevel)
          val new_numNodes = new_vv.count()

          if(new_numNodes * shrinkageThreshold < numNodes) // the small graph should have fewer nodes

          if(new_numNodes * 5 < numNodes) // the small graph should have fewer nodes
          {
            val s = ee.groupBy(DST).agg(count("*").as("count")).groupBy("count").agg(count("*").as("cnt"))
              .select(sum(col("count") * col("count") * col("cnt")).as(TMP)).agg(sum(TMP)).first().getLong(0)

            if(s < edgeCnt * 10)
            {
              opt = true
              numNodes = new_numNodes
              val je = ee.union(new_vv.withColumn(DST, col(SRC))) 
              old_ee = ee
              // edges set of the small graph
              ee = je.join(je.withColumnRenamed(SRC, TMP), DST)
                .select(SRC, TMP).withColumnRenamed(TMP, DST)
                .filter(col(SRC) < col(DST))
                .distinct() // src < dst
              new_vv = new_vv.withColumnRenamed(SRC, ID)
            }  
          }
          attempt = attempt + 1
        }
      }
*/
      logInfo(s"In iteration $iteration: edge cnt: $edgeCnt , node cnt: $numNodes")//, ratio : $ratio")
      logInfo(s"$logPrefix Sum of assigned components in iteration $iteration: $currSum.")

      if (currSum == prevSum) {
        // This also covers the case when cnt = 0 and currSum is null, which means no edges.
        converged = true
      } else {
        prevSum = currSum
      }
      iteration += 1
    }
    
    if(isOptimized == true)
    {
      // connected components of the small graph
      val cc = new_vv.join(ee, new_vv(ID) === ee(DST), "left_outer")
        .select(when(ee(SRC).isNull, new_vv(ID)).otherwise(ee(SRC)).as(SRC), new_vv(ID).as(TMP))
        .persist(intermediateStorageLevel)

      // join back to get results of the original graph
      ee = cc.join(old_ee.withColumnRenamed(SRC, TMP), TMP)
        .select(SRC, DST).union(cc.withColumnRenamed(TMP, DST))
        .distinct() // src <= dst

    }

    logger.info(s"$logPrefix Connected components converged in ${iteration - 1} iterations.")
    logger.info(s"$logPrefix Join and return component assignments with original vertex IDs.")
    old_vv.join(ee, old_vv(ID) === ee(DST), "left_outer")
      .select(old_vv(ATTR), when(ee(SRC).isNull, old_vv(ID)).otherwise(ee(SRC)).as(COMPONENT))
      .select(col(s"$ATTR.*"), col(COMPONENT))
      .persist(intermediateStorageLevel)
  }
}
