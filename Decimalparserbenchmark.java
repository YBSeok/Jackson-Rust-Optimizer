package io.snapdecimal.bench;

import io.snapdecimal.core.DecimalParser;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * JMH 벤치마크 — decimal-rs vs Java 기본 BigDecimal 파싱 비교
 *
 * <h3>측정 항목</h3>
 * <ul>
 *   <li>처리량 (ops/ms)
 *   <li>평균 레이턴시 (ns/op)
 *   <li>GC 할당량 (JMH -prof gc 옵션)
 * </ul>
 *
 * <h3>실행 방법</h3>
 * <pre>
 *   mvn clean package -P benchmark
 *   java -jar target/benchmarks.jar DecimalParserBenchmark -prof gc
 * </pre>
 *
 * <p>GC 할당량 비교가 핵심 — ops/ms보다 alloc rate가 HFT에서 더 중요합니다.
 *
 * @see <a href="https://github.com/openjdk/jmh">OpenJDK JMH</a>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgs = {
    // GC 간섭 최소화 — ZGC는 concurrent하여 HFT 환경과 유사
    "-XX:+UseZGC",
    // GC 할당 통계 수집 (JMH -prof gc와 함께 사용)
    "-Xms512m", "-Xmx512m"
})
public class DecimalParserBenchmark {

    // ── 테스트 케이스: HFT 환경에서 실제 나타나는 가격 형식
    private static final String  HFT_PRICE_STR   = "72345.67890123";
    private static final byte[]  HFT_PRICE_BYTES = HFT_PRICE_STR.getBytes(StandardCharsets.UTF_8);

    private static final String  SIMPLE_STR       = "123.45";
    private static final byte[]  SIMPLE_BYTES     = SIMPLE_STR.getBytes(StandardCharsets.UTF_8);

    private static final String  NEGATIVE_STR     = "-0.00123456";
    private static final byte[]  NEGATIVE_BYTES   = NEGATIVE_STR.getBytes(StandardCharsets.UTF_8);

    // ──────────────────────────────────────────────
    // 기준선 (Baseline): Java 표준 방식
    // Jackson이 현재 내부적으로 하는 방식과 동일
    // ──────────────────────────────────────────────

    @Benchmark
    public BigDecimal baseline_java_hftPrice() {
        // String → BigDecimal (내부적으로 char 배열 파싱)
        return new BigDecimal(HFT_PRICE_STR);
    }

    @Benchmark
    public BigDecimal baseline_java_simple() {
        return new BigDecimal(SIMPLE_STR);
    }

    @Benchmark
    public BigDecimal baseline_java_negative() {
        return new BigDecimal(NEGATIVE_STR);
    }

    // ──────────────────────────────────────────────
    // decimal-rs: Rust 네이티브 파서
    // ──────────────────────────────────────────────

    @Benchmark
    public BigDecimal decimalRs_hftPrice() {
        return DecimalParser.parse(HFT_PRICE_BYTES);
    }

    @Benchmark
    public BigDecimal decimalRs_simple() {
        return DecimalParser.parse(SIMPLE_BYTES);
    }

    @Benchmark
    public BigDecimal decimalRs_negative() {
        return DecimalParser.parse(NEGATIVE_BYTES);
    }

    // ──────────────────────────────────────────────
    // 처리량 벤치마크 (Throughput) — HFT 현실 시나리오
    // 초당 수백만 건 처리 시 throughput 차이 측정
    // ──────────────────────────────────────────────

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public BigDecimal throughput_baseline() {
        return new BigDecimal(HFT_PRICE_STR);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public BigDecimal throughput_decimalRs() {
        return DecimalParser.parse(HFT_PRICE_BYTES);
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(DecimalParserBenchmark.class.getSimpleName())
                .addProfiler("gc")  // GC 할당량 측정 — 핵심 지표
                .build();
        new Runner(opt).run();
    }
}
