package io.snapdecimal.core;

import java.math.BigDecimal;

/**
 * DecimalParser — JNI / Panama FFM 통합 인터페이스
 *
 * <p>런타임에 JDK 버전을 감지하여 최적 백엔드를 자동 선택합니다.
 * <ul>
 *   <li>JDK 22+ : Panama FFM API 사용 (JNI 대비 ~3-5x 낮은 호출 오버헤드)
 *   <li>JDK 11+ : JNI 사용 (안정적, 검증된 방식)
 * </ul>
 *
 * <h3>사용 예시 (snaptrade-engine 내부)</h3>
 * <pre>{@code
 *   // 기존 Jackson 방식 (String 중간 객체 2번 생성)
 *   BigDecimal price = new BigDecimal(jsonParser.getText());
 *
 *   // decimal-rs 방식 (zero-allocation)
 *   BigDecimal price = DecimalParser.parse(jsonParser.getValueAsBytes());
 * }</pre>
 *
 * @see <a href="https://docs.oracle.com/en/java/docs/api/java.base/java/math/BigDecimal.html">
 *      BigDecimal(long unscaledVal, int scale) constructor</a>
 * @see <a href="https://openjdk.org/jeps/454">JEP 454: Foreign Function & Memory API</a>
 */
public final class DecimalParser {

    private static final DecimalParserBackend BACKEND;

    static {
        DecimalParserBackend selected;
        try {
            int jdkVersion = Runtime.version().feature();
            if (jdkVersion >= 22) {
                // Panama FFM 백엔드 시도
                Class<?> panamaClass = Class.forName("io.snapdecimal.panama.PanamaDecimalParser");
                selected = (DecimalParserBackend) panamaClass
                        .getDeclaredMethod("getInstance")
                        .invoke(null);
                System.out.println("[decimal-rs] Backend: Panama FFM (JDK " + jdkVersion + ")");
            } else {
                throw new UnsupportedOperationException("JDK < 22, fallback to JNI");
            }
        } catch (Exception e) {
            // JNI 폴백
            selected = io.snapdecimal.jni.JniDecimalParser.getInstance();
            System.out.println("[decimal-rs] Backend: JNI (fallback)");
        }
        BACKEND = selected;
    }

    private DecimalParser() {}

    /**
     * UTF-8 바이트 배열을 직접 파싱하여 BigDecimal 반환.
     *
     *
     * @param utf8Bytes JSON 파서에서 추출한 UTF-8 바이트 (e.g. b"123.45")
     * @return 파싱된 BigDecimal
     * @throws NumberFormatException 유효하지 않은 형식
     */
    public static BigDecimal parse(byte[] utf8Bytes) {
        return BACKEND.parse(utf8Bytes);
    }

    /**
     * String 입력 지원 — 기존 코드 마이그레이션용 호환 메서드.
     */
    public static BigDecimal parse(String value) {
        return BACKEND.parse(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
