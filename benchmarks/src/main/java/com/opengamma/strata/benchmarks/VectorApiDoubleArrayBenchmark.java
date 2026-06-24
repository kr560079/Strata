/*
 * Vector API benchmarks — requires JDK 25 and --add-modules jdk.incubator.vector
 *
 * Each benchmark pairs a scalar implementation (identical to what Strata's
 * DoubleArray/DoubleArrayMath currently does) with a Vector API equivalent.
 * The difference isolates the SIMD benefit on the local CPU.
 *
 * On Apple Silicon (ARM NEON): SPECIES_PREFERRED = 128-bit = 2 doubles/cycle
 * On Intel AVX2:               SPECIES_PREFERRED = 256-bit = 4 doubles/cycle
 * On Intel AVX-512:            SPECIES_PREFERRED = 512-bit = 8 doubles/cycle
 *
 * Build with: mvn clean package -Pjdk25
 * Run with:   java --add-modules jdk.incubator.vector -jar target/benchmarks.jar VectorApi
 */
package com.opengamma.strata.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

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

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
// Pass --add-modules to the forked JVM that actually runs each benchmark
@Fork(value = 1, jvmArgsPrepend = "--add-modules jdk.incubator.vector")
@State(Scope.Benchmark)
public class VectorApiDoubleArrayBenchmark {

  private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

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

  // =========================================================================
  // Element-wise addition  (mirrors DoubleArray.plus and DoubleArrayMath.combineByAddition)
  // =========================================================================

  @Benchmark
  public double[] scalarAdd() {
    double[] result = new double[size];
    for (int i = 0; i < size; i++) {
      result[i] = a[i] + b[i];
    }
    return result;
  }

  @Benchmark
  public double[] vectorAdd() {
    double[] result = new double[size];
    int i = 0;
    int bound = SPECIES.loopBound(size);
    for (; i < bound; i += SPECIES.length()) {
      DoubleVector.fromArray(SPECIES, a, i)
          .add(DoubleVector.fromArray(SPECIES, b, i))
          .intoArray(result, i);
    }
    for (; i < size; i++) {
      result[i] = a[i] + b[i];
    }
    return result;
  }

  // =========================================================================
  // Scalar broadcast multiply  (mirrors DoubleArray.multipliedBy and
  // DoubleArrayMath.applyMultiplication)
  // =========================================================================

  @Benchmark
  public double[] scalarScale() {
    double[] result = new double[size];
    double factor = 1.5;
    for (int i = 0; i < size; i++) {
      result[i] = a[i] * factor;
    }
    return result;
  }

  @Benchmark
  public double[] vectorScale() {
    double[] result = new double[size];
    DoubleVector vfactor = DoubleVector.broadcast(SPECIES, 1.5);
    int i = 0;
    int bound = SPECIES.loopBound(size);
    for (; i < bound; i += SPECIES.length()) {
      DoubleVector.fromArray(SPECIES, a, i)
          .mul(vfactor)
          .intoArray(result, i);
    }
    for (; i < size; i++) {
      result[i] = a[i] * 1.5;
    }
    return result;
  }

  // =========================================================================
  // Dot product  (mirrors DoubleArray.combineReduce used in inner products)
  // =========================================================================

  @Benchmark
  public double scalarDotProduct() {
    double sum = 0.0;
    for (int i = 0; i < size; i++) {
      sum += a[i] * b[i];
    }
    return sum;
  }

  /**
   * Vector dot product using fused multiply-add (FMA).
   * FMA computes a[i]*b[i] + acc in a single instruction, giving both
   * throughput and better floating-point accuracy than separate mul+add.
   */
  @Benchmark
  public double vectorDotProduct() {
    DoubleVector acc = DoubleVector.zero(SPECIES);
    int i = 0;
    int bound = SPECIES.loopBound(size);
    for (; i < bound; i += SPECIES.length()) {
      DoubleVector va = DoubleVector.fromArray(SPECIES, a, i);
      DoubleVector vb = DoubleVector.fromArray(SPECIES, b, i);
      acc = va.fma(vb, acc);
    }
    double sum = acc.reduceLanes(VectorOperators.ADD);
    for (; i < size; i++) {
      sum += a[i] * b[i];
    }
    return sum;
  }

  // =========================================================================
  // Fused multiply-add  (a[i]*b[i] + c  for a constant c)
  // Mirrors DoubleArray.combine((x, y) -> x * y + constant)
  // =========================================================================

  @Benchmark
  public double[] scalarFma() {
    double[] result = new double[size];
    double addend = 1.0;
    for (int i = 0; i < size; i++) {
      result[i] = Math.fma(a[i], b[i], addend);
    }
    return result;
  }

  @Benchmark
  public double[] vectorFma() {
    double[] result = new double[size];
    DoubleVector vaddend = DoubleVector.broadcast(SPECIES, 1.0);
    int i = 0;
    int bound = SPECIES.loopBound(size);
    for (; i < bound; i += SPECIES.length()) {
      DoubleVector va = DoubleVector.fromArray(SPECIES, a, i);
      DoubleVector vb = DoubleVector.fromArray(SPECIES, b, i);
      va.fma(vb, vaddend).intoArray(result, i);
    }
    for (; i < size; i++) {
      result[i] = Math.fma(a[i], b[i], 1.0);
    }
    return result;
  }

  // =========================================================================
  // In-place accumulate  (mirrors DoubleArrayMath.mutateByAddition)
  // =========================================================================

  @Benchmark
  public double[] scalarAccumulate() {
    double[] result = a.clone();
    for (int i = 0; i < size; i++) {
      result[i] += b[i];
    }
    return result;
  }

  @Benchmark
  public double[] vectorAccumulate() {
    double[] result = a.clone();
    int i = 0;
    int bound = SPECIES.loopBound(size);
    for (; i < bound; i += SPECIES.length()) {
      DoubleVector.fromArray(SPECIES, result, i)
          .add(DoubleVector.fromArray(SPECIES, b, i))
          .intoArray(result, i);
    }
    for (; i < size; i++) {
      result[i] += b[i];
    }
    return result;
  }
}
