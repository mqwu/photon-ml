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
package com.linkedin.photon.ml.function.glm

import breeze.linalg.{DenseVector, Vector, axpy}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD

import com.linkedin.photon.ml.data.LabeledPoint
import com.linkedin.photon.ml.normalization.NormalizationContext
import com.linkedin.photon.ml.util.BroadcastWrapper

// TODO: Better document this algorithm, especially normalization.
/**
 * An aggregator to perform calculation on value and gradient for generalized linear model loss function, especially
 * in the context of normalization. Both iterable data and rdd data share the same logic for data aggregate.
 *
 * @param func A single loss function for the generalized linear model
 * @param dim Dimension of the aggregator (# of features)
 */
@SerialVersionUID(1L)
protected[ml] class ValueAndGradientAggregator(func: PointwiseLossFunction, val dim: Int) extends Serializable {

  // effectiveCoef = coef .* factor (point wise multiplication)
  // This is an intermediate vector to facilitate evaluation of value and gradient (and Hessian vector multiplication)
  // E.g., to calculate the margin:
  //     \sum_j coef_j * (x_j - shift_j) * factor_j
  //   = \sum_j coef_j * factor_j * x_j - \sum_j coef_j * factor_j * shift_j
  //   = \sum_j effectiveCoef_j * x_j - \sum_j effectiveCoef_j * shift_j
  //   = effectiveCoef^T x - effectiveCoef^T shift
  // This vector is data point independent.
  @transient protected var effectiveCoefficients: Vector[Double] = _

  // Calculate: - effectiveCoef^T shift
  // This quantity is used to calculate the margin = effectiveCoef^T x - effectiveCoef^T shift
  // This value is datapoint independent.
  @transient protected var marginShift: Double = _

  // Total count
  protected var totalCnt = 0L

  // The accumulator to calculate the scaler.
  // For DiffFunction, this is \sum l(z_i, y_i) which sums up to objective value
  // For TwiceDiffFunction, this is not used
  protected var valueSum = 0.0d

  // The accumulator to calculate the principal part of the vector.
  // For DiffFunction, this is \sum l' x_{ji}, which sums up to the gradient without shifts and scaling
  //     gradient_j = \sum_i l'(z_i, y_i) (x_{ji} - shift_j) * factor_j
  //                = factor_j * [\sum_i l'(z_i, y_i) x_{ji} - shift_j * \sum_i l'(z_i, y_i)]
  //                = factor_j * [vectorSum - shift_j * \sum_i l'(z_i, y_i)]
  // For TwiceDiffFunction, this is \sum l''[(x-shift) * factor v] x, which sums up to the principal part of the Hessian
  // vector product
  //     hv_j = \sum_ik (x_{ji} - shift_j) * factor_j * l''(z_i, y_i) * (x_{ki} - shift_k) * factor_k * v_k
  //          = \sum_i (x_{ji} - shift_j) * factor_j * l''(z_i, y_i) * \sum_k (x_{ki} - shift_k) * factor_k * v_k
  //          = factor_j * [\sum_i x_{ji} * l''(z_i, y_i) * \sum_k (x_{ki} - shift_k) * factor_k * v_k
  //                      - shift_j * \sum_i x_{ji} * l''(z_i, y_i) * \sum_k (x_{ki} - shift_k) * factor_k * v_k]
  //          = factor_j *
  //              [vectorSum - shift_j * \sum_i x_{ji} * l''(z_i, y_i) * \sum_k (x_{ki} - shift_k) * factor_k * v_k]
  protected var vectorSum: Vector[Double] = _

  // The accumulator to calculate the prefactor of the vector shift.
  // For DiffFunction, this is \sum l', which sums up to the prefactor for gradient shift
  //      gradient_j = factor_j * [vectorSum - shift_j * vectorShiftPrefactorSum]
  // For TwiceDiffFunction, this is \sum_i l''(z_i, y_i) * \sum_k (x_{ki} - shift_k) * factor_k * v_k,
  // which sums up to the prefactor for Hessian vector product shift
  //      hv_j = factor_j * (vectorSum - shift_j * vectorShiftPrefactorSum)
  protected var vectorShiftPrefactorSum = 0.0d

  protected var initialized: Boolean = false

  /**
   * Initialize the aggregator with proper coefficient vector and margin shifts if normalization is used.
   *
   * @param coef The current model coefficients
   * @param normalizationContext The normalization context
   * @return The aggregator
   */
  def init(coef: Vector[Double], normalizationContext: NormalizationContext): Unit = {

    // The transformation for a feature will be
    // x_i' = (x_i - shift_i) * factor_i
    val NormalizationContext(factorsOpt, shiftsAndInterceptOpt) = normalizationContext

    effectiveCoefficients = factorsOpt match {
      case Some(factors) =>
        shiftsAndInterceptOpt.foreach { case (_, intercept) =>
          require(
            factors(intercept) == 1.0,
            s"The intercept should not be transformed. Intercept scaling factor: ${factors(intercept)}")
        }
        require(factors.size == dim, s"Size mismatch. Factors vector size: ${factors.size} != $dim.")

        coef :* factors

      case None =>
        coef
    }

    marginShift = shiftsAndInterceptOpt match {
      case Some((shifts, intercept)) =>
        require(
          shifts(intercept) == 0.0,
          s"The intercept should not be transformed. Intercept shift: ${shifts(intercept)}")

        - effectiveCoefficients.dot(shifts)

      case None =>
        0.0
    }

    if (vectorSum == null) {
      vectorSum = DenseVector.zeros[Double](dim)
    }
  }

  /**
   * Add a data point to the aggregator.
   *
   * @param datum The data point
   * @param coef The current model coefficients
   * @param normalizationContext The normalization context
   * @return The aggregator itself
   */
  def add(datum: LabeledPoint, coef: Vector[Double], normalizationContext: NormalizationContext): this.type = {

    if (!initialized) {
      this.synchronized {
        init(coef, normalizationContext)
        initialized = true
      }
    }

    val LabeledPoint(label, features, _, weight) = datum

    require(
      features.size == effectiveCoefficients.size,
      s"Size mismatch. Coefficient size: ${effectiveCoefficients.size}, features size: ${features.size}")

    val margin = datum.computeMargin(effectiveCoefficients) + marginShift
    val (loss, dzLoss) = func.lossAndDzLoss(margin, label)

    totalCnt += 1
    valueSum += weight * loss
    vectorShiftPrefactorSum += weight * dzLoss
    axpy(weight * dzLoss, features, vectorSum)

    this
  }

  /**
   * Merge two aggregators.
   *
   * @param that The other aggregator
   * @return A merged aggregator
   */
  def merge(that: ValueAndGradientAggregator): this.type = {

    require(dim == that.dim, s"Dimension mismatch. this.dim=$dim, that.dim=${that.dim}")
    require(that.getClass.eq(getClass), s"Class mismatch. this.class=$getClass, that.class=${that.getClass}")

    if (vectorSum == null) {
      vectorSum = DenseVector.zeros[Double](dim)
    }

    if (that.totalCnt != 0) {
      totalCnt += that.totalCnt
      valueSum += that.valueSum
      vectorShiftPrefactorSum += that.vectorShiftPrefactorSum
      axpy(1.0, that.vectorSum, vectorSum)
    }

    this
  }

  /**
   * Get the count.
   *
   * @return The count
   */
  def count: Long = totalCnt

  /**
   * Return the objective value for ValueAndGradientAggregator. Not used in the HessianVectorAggregator.
   *
   * @return Return the objective value
   */
  def getValue: Double = valueSum

  /**
   * Return the cumulative gradient for ValueAndGradientAggregator, or the Hessian vector product for
   * HessianVectorAggregator, especially in the context of normalization.
   *
   * @param normalizationContext The normalization context
   * @return The cumulative gradient
   */
  def getVector(normalizationContext: NormalizationContext): Vector[Double] = {

    val NormalizationContext(factorsOpt, shiftsAndInterceptOpt) = normalizationContext

    (factorsOpt, shiftsAndInterceptOpt) match {
      case (Some(factors), Some((shifts, _))) =>
        (vectorSum :- (shifts * vectorShiftPrefactorSum)) :* factors

      case (Some(factors), None) =>
        vectorSum :* factors

      case (None, Some((shifts, _))) =>
        vectorSum :- (shifts * vectorShiftPrefactorSum)

      case (None, None) =>
        vectorSum
    }
  }
}

object ValueAndGradientAggregator {
  /**
   * Calculate the value and gradient for an objective function in Spark.
   *
   * @param input An RDD of data points
   * @param coef The current model coefficients
   * @param singleLossFunction The function used to compute loss for predictions
   * @param normalizationContext The normalization context
   * @param treeAggregateDepth The tree aggregate depth
   * @return A tuple of the value and gradient
   */
  def calculateValueAndGradient(
      input: RDD[LabeledPoint],
      coef: Broadcast[Vector[Double]],
      singleLossFunction: PointwiseLossFunction,
      normalizationContext: BroadcastWrapper[NormalizationContext],
      treeAggregateDepth: Int): (Double, Vector[Double]) = {

    val aggregator = new ValueAndGradientAggregator(singleLossFunction, coef.value.size)
    val resultAggregator = input.treeAggregate(aggregator)(
      seqOp = (ag, datum) => ag.add(datum, coef.value, normalizationContext.value),
      combOp = (ag1, ag2) => ag1.merge(ag2),
      depth = treeAggregateDepth
    )

    (resultAggregator.getValue, resultAggregator.getVector(normalizationContext.value))
  }

  /**
   * Calculate the value and gradient for an objective function locally.
   *
   * @param input An iterable set of data points
   * @param coef The current model coefficients
   * @param singleLossFunction The function used to compute loss for predictions
   * @param normalizationContext The normalization context
   * @return A tuple of the value and gradient
   */
  def calculateValueAndGradient(
      input: Iterable[LabeledPoint],
      coef: Vector[Double],
      singleLossFunction: PointwiseLossFunction,
      normalizationContext: BroadcastWrapper[NormalizationContext]): (Double, Vector[Double]) = {

    val aggregator = new ValueAndGradientAggregator(singleLossFunction, coef.size)
    val resultAggregator = input.aggregate(aggregator)(
      seqop = (ag, datum) => ag.add(datum, coef, normalizationContext.value),
      combop = (ag1, ag2) => ag1.merge(ag2)
    )

    (resultAggregator.getValue, resultAggregator.getVector(normalizationContext.value))
  }
}
