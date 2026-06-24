/*
 * Benchmarks for com.opengamma.strata.collect.DoubleArrayMath
 *
 * Tests the raw double[] utility methods that underpin DoubleArray's
 * element-wise operations. These are called from curve calibration,
 * sensitivity aggregation, and data-loading paths.
 */
package com.opengamma.strata.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import com.opengamma.strata.collect.DoubleArrayMath;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class DoubleArrayMathBenchmark {

  @Param({"32", "256", "1024"})
  public int size;

  private double[] a;
  private double[] b;

  @Setup(Level.Trial)
  public void setup() {
    Random rng = new Random(42);
    a = new double[size];
    b = new double[size];
    for (int i = 0; i < size; i++) {
      a[i] = rng.nextDouble();
      b[i] = rng.nextDouble() + 0.01;
    }
  }

  // ---- Aggregation ---------------------------------------------------------

  /** Sum all elements in a raw double[]. */
  @Benchmark
  public double sum() {
    return DoubleArrayMath.sum(a);
  }

  // ---- Returning-new-array operations -------------------------------------

  /** Element-wise addition of two double[] arrays. */
  @Benchmark
  public double[] combineByAddition() {
    return DoubleArrayMath.combineByAddition(a, b);
  }

  /** Element-wise multiplication of two double[] arrays. */
  @Benchmark
  public double[] combineByMultiplication() {
    return DoubleArrayMath.combineByMultiplication(a, b);
  }

  /** Scale every element by a constant scalar. */
  @Benchmark
  public double[] applyMultiplication() {
    return DoubleArrayMath.applyMultiplication(a, 1.5);
  }

  /** Add a constant to every element. */
  @Benchmark
  public double[] applyAddition() {
    return DoubleArrayMath.applyAddition(a, 0.001);
  }

  // ---- In-place (mutating) operations -------------------------------------

  /**
   * In-place addition — used for accumulating sensitivities into a mutable
   * working buffer. Avoids an allocation, so typically faster than combineByAddition.
   */
  @Benchmark
  public double[] mutateByAddition() {
    // Work on a fresh copy each time so the benchmark is repeatable
    double[] copy = a.clone();
    DoubleArrayMath.mutateByAddition(copy, b);
    return copy;
  }

  /** In-place multiply each element by a scalar. */
  @Benchmark
  public double[] mutateByMultiplication() {
    double[] copy = a.clone();
    DoubleArrayMath.mutateByMultiplication(copy, 1.5);
    return copy;
  }
}
