# JDK 25 Enhancement Plan for Strata

## Context

The codebase has 3,215 Java source files across 11 modules. The Maven build currently compiles to
`--release 8` (enforced via `maven.compiler.release=8` in `modules/pom.xml`). Strata is a numerical
financial library: the hot paths are in `modules/math`, `modules/pricer`, and
`modules/collect/array`. Performance improvements in those loops matter directly to pricing
throughput.

---

## Step 0 — Compiler Target Upgrade (Prerequisite)

**File:** `modules/pom.xml`, line 502  
**Change:** Update `maven.compiler.release` from `8` to `25` in both the base properties and the
`forcejavaversion` profile. Also update the `requireJavaVersion` enforcer rule from `[1.8,)` to
`[25,)`.

Without this, none of the language features below are accessible. The `forbiddenapis` plugin
`jdk-deprecated` bundle will also need review — some APIs deprecated between Java 8 and 25 may
need to be updated (e.g., `Thread.setDaemon`, which is still valid in 25 but the plugin's checks
will flag more things).

---

## Step 1 — Vector API for DoubleArray / DoubleArrayMath (Highest Performance Impact)

**Target files:**
- `modules/collect/src/main/java/com/opengamma/strata/collect/array/DoubleArray.java`
- `modules/collect/src/main/java/com/opengamma/strata/collect/DoubleArrayMath.java`

**What's there now:** `DoubleArray.plus()`, `minus()`, `multipliedBy()`, `dividedBy()`, `map()`,
`combine()`, `reduce()` all use hand-written scalar `for (int i = 0; i < array.length; i++)`
loops. These are called from every pricer in the system — Black-Scholes, Hull-White, SABR, CDS,
swaptions.

**JDK 25 feature:** Vector API (incubating, requires `--add-modules jdk.incubator.vector`).
Rewrite the element-wise operations with `DoubleVector` operations:

```java
// Before (DoubleArray.plus)
for (int i = 0; i < array.length; i++) {
    result[i] = array[i] + other.array[i];
}

// After
var species = DoubleVector.SPECIES_PREFERRED;
int i = 0;
for (; i < species.loopBound(array.length); i += species.length()) {
    var va = DoubleVector.fromArray(species, array, i);
    var vb = DoubleVector.fromArray(species, other.array, i);
    va.add(vb).intoArray(result, i);
}
for (; i < array.length; i++) result[i] = array[i] + other.array[i]; // tail
```

The `SPECIES_PREFERRED` width selects the widest SIMD register available at runtime (256-bit AVX2
= 4 doubles per cycle, 512-bit AVX-512 = 8 per cycle). The JVM cannot auto-vectorize these loops
currently because the lambdas (`DoubleUnaryOperator`, `DoubleBinaryOperator`) prevent it. The
Vector API makes it explicit.

**Maven change needed:** Add `--add-modules jdk.incubator.vector` to `maven-compiler-plugin` and
Surefire args. Also update the `forbiddenapis` config to not flag incubator APIs.

**Impact estimate:** 2–8× speedup on element-wise array operations. Since pricing paths call these
repeatedly (curve bootstrapping, scenario calculations with hundreds of scenarios), this directly
reduces total calculation time.

---

## Step 2 — Pattern Matching `instanceof` (486 occurrences)

**Scope:** All of `modules/*/src/main/java`

**What's there now:** Every `instanceof` check in the codebase (486 in main source) immediately
follows with an explicit cast:

```java
// DefaultCalculationTaskRunner.java:132
if (!(value instanceof ScenarioArray)) { return result; }
ScenarioArray<?> scenarioResult = (ScenarioArray<?>) value;  // redundant cast

// ZeroRateSensitivity.java:114
if (other instanceof ZeroRateSensitivity) {
    ZeroRateSensitivity otherS = (ZeroRateSensitivity) other; // redundant cast
```

**JDK 25 feature:** Pattern matching for `instanceof` (standard since JDK 16):

```java
if (value instanceof ScenarioArray<?> scenarioResult) { ... }
if (other instanceof ZeroRateSensitivity otherS) { ... }
```

**Rollout order:** Start with `modules/calc/runner/DefaultCalculationTaskRunner.java`, then
`modules/pricer` (25 sensitivity files), then `modules/basics`.

---

## Step 3 — Sealed Interfaces + Pattern Matching Switch

### 3a. `Matrix` Interface (5 Known Implementors — Closed Set)

**File:** `modules/collect/src/main/java/com/opengamma/strata/collect/array/Matrix.java`

Make `Matrix` a sealed interface:

```java
public sealed interface Matrix
    permits DoubleArray, DoubleMatrix, IntArray, LongArray, TridiagonalMatrix { ... }
```

**Target:** `OGMatrixAlgebra.java` has multiple if/else-if chains dispatching on `instanceof
DoubleArray`, `DoubleMatrix`, `TridiagonalMatrix`. With sealed + pattern matching switch (JDK 21+),
these become exhaustive:

```java
// Before (OGMatrixAlgebra.java:172-180)
if (m1 instanceof TridiagonalMatrix && m2 instanceof DoubleArray) { ... }
else if (m1 instanceof DoubleArray && m2 instanceof TridiagonalMatrix) { ... }
else if (m1 instanceof DoubleMatrix && m2 instanceof DoubleMatrix) { ... }
else if (m1 instanceof DoubleMatrix && m2 instanceof DoubleArray) { ... }
else if (m1 instanceof DoubleArray && m2 instanceof DoubleMatrix) { ... }

// After — compiler verifies exhaustiveness
return switch (m1) {
    case TridiagonalMatrix t when m2 instanceof DoubleArray a  -> multiply(t, a);
    case DoubleMatrix dm  when m2 instanceof DoubleMatrix dm2  -> multiply(dm, dm2);
    case DoubleMatrix dm  when m2 instanceof DoubleArray da    -> multiply(dm, da);
    case DoubleArray da   when m2 instanceof TridiagonalMatrix t -> multiply(da, t);
    case DoubleArray da   when m2 instanceof DoubleMatrix dm   -> multiply(da, dm);
    default -> throw new IllegalArgumentException("...");
};
```

**Note on `TridiagonalMatrix`:** It's in `modules/math` and is a non-Joda-Bean class. Sealing
`Matrix` requires `TridiagonalMatrix` to be `final`, `sealed`, or `non-sealed` — verify nothing
extends it before adding `final`.

### 3b. `PointSensitivity` Interface (18 Known Implementors — Closed Set)

**File:** `modules/market/src/main/java/com/opengamma/strata/market/sensitivity/PointSensitivity.java`

All 18 implementors live in `modules/pricer`. Declare them in a `permits` clause. Any future
type dispatch using `if/else if instanceof` chains then becomes an exhaustive switch, preventing
the silent bug where a new sensitivity type is added but a dispatch chain misses it.

**Known implementors to permit:**
`ZeroRateSensitivity`, `IborRateSensitivity`, `OvernightRateSensitivity`,
`InflationRateSensitivity`, `FxOptionSensitivity`, `FxIndexSensitivity`, `FxForwardSensitivity`,
`CreditCurveZeroRateSensitivity`, `SwaptionSabrSensitivity`, `SwaptionSensitivity`,
`BondYieldSensitivity`, `RepoCurveZeroRateSensitivity`, `IssuerCurveZeroRateSensitivity`,
`BondFutureOptionSensitivity`, `IborFutureOptionSensitivity`, `OvernightFutureOptionSensitivity`,
`IborCapletFloorletSabrSensitivity`, `IborCapletFloorletSensitivity`

**Important caveat:** The interface is in `strata-market` while all implementors are in
`strata-pricer`. The `permits` clause must list all 18 types by FQN, and the modules must have
appropriate `exports`/`opens` if JPMS is introduced.

---

## Step 4 — Virtual Threads for Calculation Task Runner

**File:** `modules/calc/src/main/java/com/opengamma/strata/calc/runner/DefaultCalculationTaskRunner.java` (lines 73–83)

**What's there now:**

```java
private static ExecutorService createExecutor(int threads) {
    ThreadFactory threadFactory = r -> {
        Thread t = defaultFactory.newThread(r);
        t.setDaemon(true);
        return t;
    };
    return Executors.newFixedThreadPool(effectiveThreads, threadFactory);
}
```

**JDK 25 feature:** Virtual Threads (standard since JDK 21). Add a factory method:

```java
static DefaultCalculationTaskRunner ofVirtualThreaded() {
    return new DefaultCalculationTaskRunner(Executors.newVirtualThreadPerTaskExecutor());
}
```

**Important nuance:** Pricing is CPU-bound, not I/O-bound. Virtual threads don't give throughput
gains over platform threads for pure computation. The primary benefits here are:

1. The async listener/callback path (`calculateMultiScenarioAsync`) may block on listener code
   that does I/O (e.g., writing results)
2. Scenario market data loading may involve blocking reads
3. Removes the artificial thread-count ceiling — the existing code caps at `availableProcessors()`,
   but with virtual threads each task can be its own thread with no OS overhead

For pure CPU pricing, the existing `newFixedThreadPool` with `availableProcessors()` threads
remains the right choice. Both options should be documented and exposed as named factories.

---

## Step 5 — Foreign Function & Memory API for Data Loading

**Target:** `modules/loader/src/main/java` and `modules/collect/src/main/java/com/opengamma/strata/collect/io/`

**What's there now:** Market data (curves, vol surfaces) is loaded via Guava's `CharSource` /
`LineProcessor`, which uses `BufferedReader` → `String` per line. Large CSV files allocate
significant heap during loading.

**JDK 25 feature:** Foreign Function & Memory API (standard since JDK 22). Large CSV files can be
memory-mapped directly:

```java
try (Arena arena = Arena.ofConfined()) {
    MemorySegment segment = FileChannel.open(path)
        .map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
    // parse segment directly without heap String allocation
}
```

This bypasses the Java heap for large file reads, reducing GC pressure during data loading. It is
particularly relevant when loading historical market data for backtesting across thousands of
scenarios.

---

## Step 6 — Structured Concurrency for Multi-Scenario Calculations (Preview in JDK 25)

**File:** `modules/calc/src/main/java/com/opengamma/strata/calc/runner/DefaultCalculationTaskRunner.java`

**What's there now:** The synchronous `calculateMultiScenario()` path creates a `ResultsListener`
and calls `calculateMultiScenarioAsync`, which submits each task via
`CompletableFuture.supplyAsync`. The main thread then blocks on `listener.result()`.

**JDK 25 feature:** `StructuredTaskScope` (fifth preview in JDK 25). Replace the fan-out pattern:

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    List<StructuredTaskScope.Subtask<CalculationResults>> subtasks = taskList.stream()
        .map(task -> scope.fork(() -> task.execute(marketData, refData)))
        .toList();
    scope.join();
    scope.throwIfFailed();
    // collect results from subtasks
}
```

Benefits: automatic cancellation propagation on failure, cleaner lifecycle, and JVM tooling can
track the structured task tree. Requires `--enable-preview` flag since this is still preview.

---

## Step 7 — Sequenced Collections and Minor Cleanups

Across the codebase, several Guava patterns can be replaced with JDK 21+ sequenced collection APIs:

- `Iterables.getLast(list)` → `list.getLast()`
- `Iterables.getFirst(list, null)` → `list.getFirst()` (where non-empty is guaranteed)

This reduces dependency on Guava for these trivial cases.

---

## Implementation Order (Prioritized)

| # | Change | Effort | Performance Impact |
|---|--------|--------|--------------------|
| 0 | Compiler target → 25 | Low | Prerequisite |
| 1 | Vector API for `DoubleArray` operations | High | **Very High** (2–8× on array ops) |
| 2 | Pattern matching `instanceof` (486 sites) | Medium | Low (cleanliness + minor JIT) |
| 3a | Sealed `Matrix` + switch in `OGMatrixAlgebra` | Medium | Low-Medium (exhaustiveness) |
| 3b | Sealed `PointSensitivity` | Medium | Low (correctness) |
| 4 | Virtual Threads factory method | Low | Medium (async paths) |
| 5 | Foreign Memory for file loading | Medium | Medium (data loading throughput) |
| 6 | Structured Concurrency (preview) | Low-Medium | Low-Medium (fault handling) |
| 7 | Sequenced Collections cleanup | Low | Negligible |

---

## Key Risks

1. **Joda-Beans generated code:** The `.java` files with `@BeanDefinition` contain auto-generated
   inner classes (`Meta`, `Builder`) using `switch (propertyName.hashCode())`. Do not edit that
   boilerplate by hand — re-run `joda-beans generate` after any change to the outer class.

2. **`forbiddenapis` plugin:** The `jdk-non-portable` bundle will flag Vector API usage. Add a
   `suppressAnnotations` or `excludes` entry for the `jdk.incubator.vector` package in
   `modules/pom.xml`.

3. **`PointSensitivity` across modules:** Sealed types require all permitted subtypes to be
   accessible from the declaring module. The split between `strata-market` (interface) and
   `strata-pricer` (18 implementors) requires explicit `permits` listing by FQN, plus `exports`
   in `module-info` files if JPMS modules are introduced.

4. **`TridiagonalMatrix` extensibility:** It is currently `public class` (not `final`). Sealing
   `Matrix` requires it to be `final`, `sealed`, or `non-sealed`. Audit the codebase and test
   suite for any subclasses before marking it `final`.

5. **Records are limited here:** The tuple types (`DoublesPair`, `ObjDoublePair`, etc.) and
   sensitivity types all use `@BeanDefinition` (Joda-Beans), which requires concrete class
   patterns incompatible with Records. Records are suitable only for new internal value types
   that do not need Joda-Beans serialization.
