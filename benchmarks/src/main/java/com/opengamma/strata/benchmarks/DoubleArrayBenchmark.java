/*
 * Benchmarks for com.opengamma.strata.collect.array.DoubleArray
 *
 * These operations form the hot path in sensitivity aggregation, curve
 * bootstrapping, and scenario pricing throughout Strata. The benchmark
 * parameter sizes reflect realistic usage:
 *   32   — discount curve (20-50 pillars)
 *   256  — vol surface row or a medium scenario set
 *   1024 — large scenario set or full vol surface
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

import com.opengamma.strata.collect.array.DoubleArray;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class DoubleArrayBenchmark {

  @Param({"32", "256", "1024"})
  public int size;

  private DoubleArray a;
  private DoubleArray b;

  @Setup(Level.Trial)
  public void setup() {
    Random rng = new Random(42);
    double[] ad = new double[size];
    double[] bd = new double[size];
    for (int i = 0; i < size; i++) {
      ad[i] = rng.nextDouble();
      bd[i] = rng.nextDouble() + 0.01; // avoid zero for divide
    }
    a = DoubleArray.ofUnsafe(ad);
    b = DoubleArray.ofUnsafe(bd);
  }

  // ---- Element-wise array+array operations --------------------------------

  /** Immutable element-wise addition — used heavily in sensitivity aggregation. */
  @Benchmark
  public DoubleArray plus() {
    return a.plus(b);
  }

  /** Immutable element-wise subtraction. */
  @Benchmark
  public DoubleArray minus() {
    return a.minus(b);
  }

  // ---- Scalar broadcast operations ----------------------------------------

  /** Scale every element by a constant — used when applying notional or discount factors. */
  @Benchmark
  public DoubleArray multipliedBy() {
    return a.multipliedBy(1.5);
  }

  /** Add a constant to every element. */
  @Benchmark
  public DoubleArray plusScalar() {
    return a.plus(0.001);
  }

  // ---- Higher-order operations (lambda-based) ------------------------------

  /**
   * Map each element through Math.exp(-x) — the core operation for computing
   * discount factors from zero rates. The lambda prevents JIT auto-vectorization,
   * making this a prime target for the Vector API.
   */
  @Benchmark
  public DoubleArray mapExp() {
    return a.map(x -> Math.exp(-x));
  }

  /**
   * Map through a sigmoid function — representative of normalisation used in
   * vol surface calibration.
   */
  @Benchmark
  public DoubleArray mapSigmoid() {
    return a.map(x -> 1.0 / (1.0 + Math.exp(-x)));
  }

  /**
   * Element-wise FMA-style combine — used in cross-gamma and sensitivity blending.
   * DoubleBinaryOperator prevents auto-vectorization.
   */
  @Benchmark
  public DoubleArray combine() {
    return a.combine(b, (x, y) -> x * y + 1.0);
  }

  // ---- Aggregation ---------------------------------------------------------

  /** Sum all elements — used when aggregating total sensitivity. */
  @Benchmark
  public double reduceSum() {
    return a.reduce(0.0, Double::sum);
  }

  /** Dot product via combineReduce — used in inner products for least-squares fitting. */
  @Benchmark
  public double dotProduct() {
    return a.combineReduce(b, (acc, x, y) -> acc + x * y);
  }
}
