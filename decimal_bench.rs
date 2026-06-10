/// cargo bench — BigDecimal 파싱 성능 측정
///
/// 비교 대상:
///   1. Rust zero-allocation parser (decimal-rs-core)
///   2. Java String → new BigDecimal(string) 방식 시뮬레이션용 참고치
///
/// 실행: cargo bench --manifest-path rust-core/Cargo.toml

use criterion::{black_box, criterion_group, criterion_main, Criterion, BenchmarkId};
use decimal_rs_core::parse_decimal;

fn bench_parse_decimal(c: &mut Criterion) {
    let cases: &[(&str, &[u8])] = &[
        ("integer",          b"123456"),
        ("small_decimal",    b"123.45"),
        ("hft_price",        b"72345.67890123"),
        ("negative",         b"-0.00123456"),
        ("large_scale",      b"9999999.99999999"),
        ("scientific",       b"1.23456789e-4"),
    ];

    let mut group = c.benchmark_group("BigDecimal Parsing");

    for (name, input) in cases {
        group.bench_with_input(
            BenchmarkId::new("rust_zero_alloc", name),
            input,
            |b, i| b.iter(|| parse_decimal(black_box(i)))
        );
    }

    group.finish();
}

criterion_group!(benches, bench_parse_decimal);
criterion_main!(benches);
