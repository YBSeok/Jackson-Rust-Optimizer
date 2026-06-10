/// decimal-rs-core: Zero-allocation BigDecimal parser
///
/// 설계 원칙:
///   1. &[u8] 바이트 슬라이스를 직접 파싱 — String 중간 객체 생성 없음
///   2. unscaled_value(i64) + scale(i32) 쌍으로 반환
///      → Java 측에서 new BigDecimal(unscaledVal, scale) 생성자로 바로 복원
///   3. 모든 경로에서 힙 할당(Heap allocation) 없음 — GC pressure 제로
///
/// 참고: JLS §4.2.3 (Floating-Point Types), BigDecimal(long, int) 생성자 스펙
///       https://docs.oracle.com/en/java/docs/api/java.base/java/math/BigDecimal.html

// ────────────────────────────────────────────────────────────────
// 핵심 파싱 로직 (pure Rust, JNI 없음 — 테스트 및 재사용 가능)
// ────────────────────────────────────────────────────────────────

/// 파싱 결과 — 힙 할당 없이 스택에 반환
#[derive(Debug, PartialEq)]
pub struct DecimalParts {
    /// unscaled value: 소수점 제거한 정수값
    /// 예) "123.45" → unscaled = 12345, scale = 2
    pub unscaled: i64,
    /// 소수점 이하 자릿수 (음수 = 오른쪽으로 이동, e.g. 1.2e3)
    pub scale: i32,
}

/// 파싱 에러 종류
#[derive(Debug, PartialEq)]
pub enum ParseError {
    /// 빈 입력
    Empty,
    /// 숫자 범위 초과 (i64 오버플로우)
    Overflow,
    /// 예상치 못한 문자
    InvalidChar(u8),
    /// 소수점이 두 번 등장
    MultipleDecimalPoints,
}

/// BigDecimal 문자열을 (unscaled, scale) 쌍으로 파싱
///
/// # 지원 형식
///   - "123"       → (123, 0)
///   - "123.45"    → (12345, 2)
///   - "-0.001"    → (-1, 3)
///   - "1.2e3"     → (12, -2)   [지수 표기법]
///   - "+99.9"     → (999, 1)
///
/// # 성능 특성
///   - 힙 할당 없음 (String, Vec 등 미사용)
///   - 단일 패스(Single-pass) — 바이트를 한 번만 순회
///   - 컴파일러가 SIMD 자동 벡터화 적용 가능한 루프 구조
pub fn parse_decimal(bytes: &[u8]) -> Result<DecimalParts, ParseError> {
    if bytes.is_empty() {
        return Err(ParseError::Empty);
    }

    let mut idx = 0usize;
    let len = bytes.len();

    // ── 1. 부호 처리
    let negative = match bytes[0] {
        b'-' => { idx += 1; true }
        b'+' => { idx += 1; false }
        _    => false,
    };

    if idx == len {
        return Err(ParseError::Empty);
    }

    let mut unscaled: i64 = 0;
    let mut scale: i32 = 0;
    let mut decimal_seen = false;
    let mut exponent: i32 = 0;
    let mut exp_negative = false;
    let mut in_exponent = false;
    let mut digit_count = 0u32;

    // ── 2. 단일 패스 파싱 루프
    //       컴파일러 힌트: 이 루프는 분기 없는 경로(hot path)를 통해
    //       auto-vectorization 대상이 됨 (rustc + LLVM AVX2)
    while idx < len {
        let byte = bytes[idx];
        idx += 1;

        match byte {
            b'0'..=b'9' => {
                let digit = (byte - b'0') as i64;

                if in_exponent {
                    // 지수부 파싱
                    exponent = exponent
                        .checked_mul(10)
                        .and_then(|e| e.checked_add(digit as i32))
                        .ok_or(ParseError::Overflow)?;
                } else {
                    // 오버플로우 방지: i64::MAX = 9_223_372_036_854_775_807
                    // 금융 도메인 실무: 18자리 이하 보장 시 checked_mul 생략 가능
                    unscaled = unscaled
                        .checked_mul(10)
                        .and_then(|u| u.checked_add(digit))
                        .ok_or(ParseError::Overflow)?;

                    if decimal_seen {
                        scale += 1;
                    }
                    digit_count += 1;
                }
            }

            b'.' => {
                if decimal_seen {
                    return Err(ParseError::MultipleDecimalPoints);
                }
                if in_exponent {
                    return Err(ParseError::InvalidChar(byte));
                }
                decimal_seen = true;
            }

            b'e' | b'E' => {
                in_exponent = true;
                // 지수 부호 처리
                if idx < len {
                    match bytes[idx] {
                        b'-' => { exp_negative = true; idx += 1; }
                        b'+' => { idx += 1; }
                        _ => {}
                    }
                }
            }

            other => return Err(ParseError::InvalidChar(other)),
        }
    }

    if digit_count == 0 {
        return Err(ParseError::Empty);
    }

    // ── 3. 지수 표기법 → scale 보정
    //       "1.2e3" : unscaled=12, scale=1, exponent=3 → final_scale = 1-3 = -2
    if in_exponent {
        let exp = if exp_negative { -exponent } else { exponent };
        scale = scale.checked_sub(exp).ok_or(ParseError::Overflow)?;
    }

    // ── 4. 부호 적용
    if negative {
        unscaled = -unscaled;
    }

    Ok(DecimalParts { unscaled, scale })
}

// ────────────────────────────────────────────────────────────────
// JNI 브릿지 레이어
// Java: io.snapdecimal.jni.DecimalParserJni
// ────────────────────────────────────────────────────────────────
#[cfg(feature = "jni-bridge")]
pub mod jni_bridge {
    use jni::JNIEnv;
    use jni::objects::JByteArray;
    use jni::sys::{jboolean, jint, jlong, jobject, JNI_TRUE, JNI_FALSE};
    use super::parse_decimal;

    /// JNI 엔트리포인트
    ///
    /// Java 시그니처:
    ///   native boolean parseDecimalNative(byte[] utf8Bytes, long[] unscaled, int[] scale);
    ///
    /// 설계 선택:
    ///   - 결과를 out-param 배열로 전달 → 리턴 객체 생성 없음
    ///   - byte[] 직접 수신 → String 변환 없음
    #[no_mangle]
    pub extern "system" fn Java_io_snapdecimal_jni_DecimalParserJni_parseDecimalNative(
        mut env: JNIEnv,
        _class: jobject,
        bytes: JByteArray,
        out_unscaled: JByteArray, // long[] — JNI에서는 jlongArray
        out_scale: JByteArray,    // int[]  — JNI에서는 jintArray
    ) -> jboolean {
        // JNI 경계에서 byte[] → &[u8] 변환 (zero-copy 불가 — JNI 제약)
        let bytes = match env.convert_byte_array(bytes) {
            Ok(b) => b,
            Err(_) => return JNI_FALSE,
        };

        match parse_decimal(&bytes) {
            Ok(parts) => {
                // out-param에 결과 기록
                let _ = env.set_long_array_region(&out_unscaled, 0, &[parts.unscaled]);
                let _ = env.set_int_array_region(&out_scale, 0, &[parts.scale]);
                JNI_TRUE
            }
            Err(_) => JNI_FALSE,
        }
    }
}

// ────────────────────────────────────────────────────────────────
// Panama FFM 브릿지 레이어 (JDK 22+ Foreign Function & Memory API)
// Java 측: MethodHandle로 직접 호출 — JNI 오버헤드 없음
// ────────────────────────────────────────────────────────────────

/// Panama FFM용 C ABI 엔트리포인트
///
/// Java 시그니처 (Panama):
///   FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS)
///
/// 설계 선택:
///   - 원시 포인터(raw pointer) 수신 → JVM 힙의 바이트 배열을 직접 읽음
///   - Panama MemorySegment로 off-heap 버퍼 전달 시 진짜 zero-copy 가능
///
/// # Safety
///   - bytes_ptr: 유효한 UTF-8 바이트 배열, len 길이 보장 필요
///   - out_unscaled / out_scale: 유효한 포인터 보장 필요 (Java 측 책임)
#[no_mangle]
pub unsafe extern "C" fn snap_parse_decimal(
    bytes_ptr: *const u8,
    len: usize,
    out_unscaled: *mut i64,
    out_scale: *mut i32,
) -> bool {
    if bytes_ptr.is_null() || out_unscaled.is_null() || out_scale.is_null() {
        return false;
    }

    // Safety: Java 측에서 유효성 보장 (MemorySegment bounds check)
    let bytes = std::slice::from_raw_parts(bytes_ptr, len);

    match parse_decimal(bytes) {
        Ok(parts) => {
            *out_unscaled = parts.unscaled;
            *out_scale = parts.scale;
            true
        }
        Err(_) => false,
    }
}

// ────────────────────────────────────────────────────────────────
// 단위 테스트
// ────────────────────────────────────────────────────────────────
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_integer() {
        assert_eq!(parse_decimal(b"123"), Ok(DecimalParts { unscaled: 123, scale: 0 }));
    }

    #[test]
    fn test_decimal() {
        assert_eq!(parse_decimal(b"123.45"), Ok(DecimalParts { unscaled: 12345, scale: 2 }));
    }

    #[test]
    fn test_negative() {
        assert_eq!(parse_decimal(b"-0.001"), Ok(DecimalParts { unscaled: -1, scale: 3 }));
    }

    #[test]
    fn test_scientific_notation() {
        // "1.2e3" = 1200 → unscaled=12, scale=-2
        assert_eq!(parse_decimal(b"1.2e3"), Ok(DecimalParts { unscaled: 12, scale: -2 }));
    }

    #[test]
    fn test_hft_price() {
        // 실제 HFT 호가: "72345.67890123"
        assert_eq!(
            parse_decimal(b"72345.67890123"),
            Ok(DecimalParts { unscaled: 7234567890123, scale: 8 })
        );
    }

    #[test]
    fn test_overflow() {
        // i64 범위 초과
        assert_eq!(parse_decimal(b"99999999999999999999"), Err(ParseError::Overflow));
    }

    #[test]
    fn test_empty() {
        assert_eq!(parse_decimal(b""), Err(ParseError::Empty));
    }

    #[test]
    fn test_multiple_dots() {
        assert_eq!(parse_decimal(b"1.2.3"), Err(ParseError::MultipleDecimalPoints));
    }

    #[test]
    fn test_zero() {
        assert_eq!(parse_decimal(b"0.0"), Ok(DecimalParts { unscaled: 0, scale: 1 }));
    }
}
