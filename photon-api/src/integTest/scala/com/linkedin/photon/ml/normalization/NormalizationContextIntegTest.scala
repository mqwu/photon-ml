/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.normalization

import java.net.URL
import java.nio.file.{Files, Paths}

import scala.util.Random

import breeze.linalg.{DenseVector, SparseVector, Vector}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.testng.Assert._
import org.testng.annotations.{DataProvider, Test}

import com.linkedin.photon.ml.{ModelTraining, TaskType}
import com.linkedin.photon.ml.data.LabeledPoint
import com.linkedin.photon.ml.function.DistributedObjectiveFunction
import com.linkedin.photon.ml.function.glm.{DistributedGLMLossFunction, LogisticLossFunction, PoissonLossFunction, SquaredLossFunction}
import com.linkedin.photon.ml.model.Coefficients
import com.linkedin.photon.ml.normalization.NormalizationType.NormalizationType
import com.linkedin.photon.ml.optimization._
import com.linkedin.photon.ml.optimization.game.FixedEffectOptimizationConfiguration
import com.linkedin.photon.ml.stat.FeatureDataStatistics
import com.linkedin.photon.ml.supervised.classification.{BinaryClassifier, LogisticRegressionModel}
import com.linkedin.photon.ml.test.Assertions.assertIterableEqualsWithTolerance
import com.linkedin.photon.ml.test.{CommonTestUtils, SparkTestUtils}
import com.linkedin.photon.ml.util.{GameTestUtils, PhotonNonBroadcast}

/**
 * Integration tests for [[NormalizationContext]].
 */
class NormalizationContextIntegTest extends SparkTestUtils with GameTestUtils {

  import NormalizationContextIntegTest._

  /**
   * Generate sample data for binary classification problems according to a random seed and a model. The labels in the
   * data are exactly predicted by the input model.
   *
   * @param sc The [[SparkContext]] for the test
   * @param seed The random seed used to generate the data
   * @param model The input model used to generate the labels in the data
   * @return The data RDD
   */
  private def generateSampleRDD(sc: SparkContext, seed: Int, model: LogisticRegressionModel): RDD[LabeledPoint] = {

    val data = drawBalancedSampleFromNumericallyBenignDenseFeaturesForBinaryClassifierLocal(seed, SIZE, DIMENSION)
      .map { case (_, sparseVector: SparseVector[Double]) =>
        // Append data with the intercept
        val size = sparseVector.size
        val vector = new SparseVector[Double](
          sparseVector.index :+ size,
          sparseVector.data :+ 1.0,
          size + 1)

        // Replace the random label using the one predicted by the input model
        val label = model.predictClass(vector, THRESHOLD)

        new LabeledPoint(label, vector)
      }
      .toArray

    sc.parallelize(data)
  }

  /**
   * Check the correctness of training with a specific normalization type. This check involves unregularized training.
   * The prediction of an unregularized trained model should predict exactly the same label as the one in the input
   * training data, given that the labels are generated by a model without noise. This method also checks the precision
   * of a test dataset.
   *
   * @param trainRDD Training dataset
   * @param testRDD Test dataset
   * @param normalizationType Normalization type
   */
  private def checkTrainingOfNormalizationType(
      trainRDD: RDD[LabeledPoint],
      testRDD: RDD[LabeledPoint],
      normalizationType: NormalizationType): Unit = {

    // This is necessary to make Spark not complain serialization error of this class.
    val summary = FeatureDataStatistics(trainRDD, Some(DIMENSION))
    val normalizationContext = NormalizationContext(normalizationType, summary)
    val threshold = THRESHOLD
    val models = ModelTraining.trainGeneralizedLinearModel(
      trainRDD,
      TaskType.LOGISTIC_REGRESSION,
      OptimizerType.LBFGS,
      L2RegularizationContext,
      regularizationWeights = List(0.0),
      normalizationContext,
      NUM_ITER,
      CONVERGENCE_TOLERANCE,
      enableOptimizationStateTracker = true,
      constraintMap = None,
      treeAggregateDepth = 1,
      useWarmStart = false)

    assertEquals(models.size, 1)

    val model = models.head._2.asInstanceOf[BinaryClassifier]

    // For all types of normalization, the un-regularized trained model should predictClass the same label.
    trainRDD.foreach { case LabeledPoint(label, vector, _, _) =>
      val prediction = model.predictClass(vector, threshold)
      assertEquals(prediction, label)
    }

    // For a test dataset, the trained model should recover a certain level of precision.
    val correct = testRDD
      .filter { case LabeledPoint(label, vector, _, _) =>
        val prediction = model.predictClass(vector, threshold)
        label == prediction
      }
      .count

    assertTrue(correct.toDouble / SIZE >= PRECISION, s"Precision check [$correct/$SIZE >= $PRECISION] failed.")
  }

  /**
   * Tests that the results of training with each type of normalization result in the same prediction. This class is
   * mostly a driver for [[checkTrainingOfNormalizationType]] which is the real workhorse.
   */
  @Test
  def testNormalization(): Unit = sparkTest("testNormalization") {

    Random.setSeed(SEED)

    // The size of the vector is DIMENSION + 1 due to the intercept
    val coef = (for (_ <- 0 to DIMENSION) yield Random.nextGaussian()).toArray
    val model = new LogisticRegressionModel(Coefficients(DenseVector(coef)))
    val trainRDD = generateSampleRDD(sc, SEED, model)
    val testRDD = generateSampleRDD(sc, SEED + 1, model)

    NormalizationType.values.foreach(checkTrainingOfNormalizationType(trainRDD, testRDD, _))
  }

  /**
   * Read a file as a [[String]] of bytes.
   *
   * @param path The URL of the path in the local filesystem
   * @return The contents of the file as a String
   */
  private def readFileAsString(path: URL): String = {

    val data = Files.readAllBytes(Paths.get(path.toURI))

    new String(data)
  }

  /**
   * Convert a [[String]] representing a vector of numbers into a [[Vector]] of [[Double]].
   *
   * @param string The input [[String]] to convert
   * @return The converted [[Vector]]
   */
  private def stringToVector(string: String): Vector[Double] = DenseVector(string.split(',').map(_.toDouble))

  @DataProvider(name = "generateStandardizationTestData")
  def generateStandardizationTestData(): Array[Array[Any]] = {

    val heartDataInputPath = getClass.getClassLoader.getResource("DriverIntegTest/input/heart.txt")
    val heartDataInput = readFileAsString(heartDataInputPath).split('\n')
    val heartData = heartDataInput.map { line =>
      val y = line.split(" ")
      val label = y(0).toDouble / 2 + 0.5
      val features = y.drop(1).map(z => z.split(":")(1).toDouble) :+ 1.0

      new LabeledPoint(label, DenseVector(features))
    }

    val heartSummaryInputPath = getClass.getClassLoader.getResource("DriverIntegTest/input/heart_summary.txt")
    val heartSummaryInput = readFileAsString(heartSummaryInputPath).split('\n')
    val mean = stringToVector(heartSummaryInput(0))
    val heartSummary = new FeatureDataStatistics(
      heartData.length,
      mean,
      stringToVector(heartSummaryInput(1)),
      stringToVector(heartSummaryInput(2)),
      stringToVector(heartSummaryInput(3)),
      stringToVector(heartSummaryInput(4)),
      stringToVector(heartSummaryInput(5)),
      stringToVector(heartSummaryInput(6)),
      stringToVector(heartSummaryInput(7)),
      Some(mean.length - 1))

    val normalizationContext = NormalizationContext(NormalizationType.STANDARDIZATION, heartSummary)
    val normalizationBroadcast = PhotonNonBroadcast(normalizationContext)
    val noNormalizationBroadcast = PhotonNonBroadcast(NoNormalization())

    val configuration = FixedEffectOptimizationConfiguration(generateOptimizerConfig())

    val testData = for (optimizerType <- OptimizerType.values;
                        taskType<- TaskType.values.filterNot(_ == TaskType.SMOOTHED_HINGE_LOSS_LINEAR_SVM)) yield {

      val objectiveFunction = taskType match {
        case TaskType.LOGISTIC_REGRESSION =>
          DistributedGLMLossFunction(configuration, LogisticLossFunction, treeAggregateDepth = 1)

        case TaskType.LINEAR_REGRESSION =>
          DistributedGLMLossFunction(configuration, SquaredLossFunction, treeAggregateDepth = 1)

        case TaskType.POISSON_REGRESSION =>
          DistributedGLMLossFunction(configuration, PoissonLossFunction, treeAggregateDepth = 1)
      }
      val optimizerNorm = optimizerType match {
        case OptimizerType.LBFGS =>
          new LBFGS(tolerance = 1.0E-6, maxNumIterations = 100, normalizationContext = normalizationBroadcast)

        case OptimizerType.TRON =>
          new TRON(tolerance = 1.0E-6, maxNumIterations = 100, normalizationContext = normalizationBroadcast)
      }
      val optimizerNoNorm = optimizerType match {
        case OptimizerType.LBFGS =>
          new LBFGS(tolerance = 1.0E-6, maxNumIterations = 100, normalizationContext = noNormalizationBroadcast)

        case OptimizerType.TRON =>
          new TRON(tolerance = 1.0E-6, maxNumIterations = 100, normalizationContext = noNormalizationBroadcast)
      }

      Array[Any](heartData, normalizationContext, objectiveFunction, optimizerNorm, optimizerNoNorm)
    }

    testData.toArray
  }

  /**
   * This is a sophisticated test for standardization with the heart dataset. An objective function with normal input
   * and a loss function with standardization context should produce the same result as an objective function with
   * standardized input and a plain loss function. The heart dataset seems to be well-behaved, so the final objective
   * and the model coefficients can be reproduced even after many iterations.
   *
   * @param inputData The input data (should only be loaded once, but needs to be passed each time to be converted to
   *                  [[RDD]])
   * @param normalizationContext The normalization context for the input data
   * @param objectiveFunction The objective function for which to optimize
   * @param optimizerNorm The [[Optimizer]] to use (with normalization)
   * @param optimizerNoNorm The [[Optimizer]] to use (without normalization)
   */
  @Test(dataProvider = "generateStandardizationTestData")
  def testOptimizationWithStandardization(
      inputData: Array[LabeledPoint],
      normalizationContext: NormalizationContext,
      objectiveFunction: DistributedObjectiveFunction,
      optimizerNorm: Optimizer[DistributedObjectiveFunction],
      optimizerNoNorm: Optimizer[DistributedObjectiveFunction]): Unit = sparkTest("testObjectivesAfterNormalization") {

    // Read heart data
    val heartDataRDD: RDD[LabeledPoint] = sc.parallelize(inputData)
    // Build the transformed rdd for validation
    val transformedRDD: RDD[LabeledPoint] = {
      val transformedRDD = heartDataRDD
        .map { case LabeledPoint(label, features, weight, offset) =>
          new LabeledPoint(label, transformVector(normalizationContext, features), weight, offset)
        }
        .persist()

      // Verify that the transformed rdd will have the correct transformation condition
      val summaryAfterStandardization = FeatureDataStatistics(transformedRDD, Some(normalizationContext.size - 1))
      val dim = summaryAfterStandardization.mean.size

      summaryAfterStandardization
        .mean
        .toArray
        .dropRight(1)
        .foreach(x => assertEquals(0.0, x, CommonTestUtils.HIGH_PRECISION_TOLERANCE))
      summaryAfterStandardization
        .variance
        .toArray
        .dropRight(1)
        .foreach(x => assertEquals(1.0, x, CommonTestUtils.HIGH_PRECISION_TOLERANCE))
      assertEquals(1.0, summaryAfterStandardization.mean(dim - 1), CommonTestUtils.HIGH_PRECISION_TOLERANCE)
      assertEquals(0.0, summaryAfterStandardization.variance(dim - 1), CommonTestUtils.HIGH_PRECISION_TOLERANCE)

      transformedRDD
    }

    // Train the original data with a loss function binding normalization
    val zero = Vector.zeros[Double](objectiveFunction.domainDimension(heartDataRDD))
    val (model1, objective1) = optimizerNorm.optimize(objectiveFunction, zero)(heartDataRDD)
    // Train the transformed data with a normal loss function
    val (model2, objective2) = optimizerNoNorm.optimize(objectiveFunction, zero)(transformedRDD)

    heartDataRDD.unpersist()
    transformedRDD.unpersist()

    // The two objective function/optimization should be exactly the same up to numerical accuracy.
    assertEquals(objective1, objective2, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
    assertIterableEqualsWithTolerance(model1.toArray, model2.toArray, CommonTestUtils.HIGH_PRECISION_TOLERANCE)
  }
}

object NormalizationContextIntegTest {

  private val SEED = 1
  private val SIZE = 100
  private val DIMENSION = 10
  private val THRESHOLD = 0.5
  private val CONVERGENCE_TOLERANCE = 1E-5
  private val NUM_ITER = 100
  private val PRECISION = 0.95

  /**
   * For testing purpose only. This is not designed to be efficient. This method transforms a vector from the original
   * space to a normalized space.
   *
   * @param input Input vector
   * @return Transformed vector
   */
  def transformVector(normalizationContext: NormalizationContext, input: Vector[Double]): Vector[Double] =
    (normalizationContext.factorsOpt, normalizationContext.shiftsAndInterceptOpt) match {
      case (Some(fs), Some((ss, _))) =>
        require(fs.size == input.size, "Vector size and the scaling factor size are different.")
        (input - ss) *:* fs

      case (Some(fs), None) =>
        require(fs.size == input.size, "Vector size and the scaling factor size are different.")
        input *:* fs

      case (None, Some((ss, _))) =>
        require(ss.size == input.size, "Vector size and the scaling factor size are different.")
        input - ss

      case (None, None) =>
        input
    }
}
