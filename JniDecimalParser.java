package io.snapdecimal.jni;

import io.snapdecimal.core.DecimalParserBackend;
import java.math.BigDecimal;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * JNI 백엔드 — Rust 공유 라이브러리(.so/.dll/.dylib) 로드 및 호출.
 *
 * <h3>JNI 경계에서의 메모리 흐름</h3>
 * <pre>
 *   Java byte[]
 *      │ JNI GetByteArrayElements (또는 GetPrimitiveArrayCritical)
 *      ▼
 *   Rust &[u8] (단일 패스 파싱)
 *      │ out-param: long[1], int[1]
 *      ▼
 *   Java: new BigDecimal(unscaled, scale)  ← String 없음
 * </pre>
 *
 * <h3>JNI vs Panama 비교</h3>
 * <ul>
 *   <li>JNI: JDK 11+ 지원, 검증된 방식, 호출 당 ~100-200ns 오버헤드
 *   <li>Panama: JDK 22+ 전용, ~30-50ns 오버헤드, 미래 표준
 * </ul>
 */
public final class JniDecimalParser implements DecimalParserBackend {

    private static final JniDecimalParser INSTANCE = new JniDecimalParser();

    // out-param 재사용 버퍼 — ThreadLocal로 GC 압력 추가 제거
    // JNI 호출당 long[1], int[1] 생성 방지
    private static final ThreadLocal<long[]> OUT_UNSCALED = ThreadLocal.withInitial(() -> new long[1]);
    private static final ThreadLocal<int[]>  OUT_SCALE    = ThreadLocal.withInitial(() -> new int[1]);

    static {
        loadNativeLibrary();
    }

    private JniDecimalParser() {}

    public static JniDecimalParser getInstance() {
        return INSTANCE;
    }

    /**
     * UTF-8 바이트 배열을 Rust 파서로 처리하여 BigDecimal 반환.
     *
     * @throws NumberFormatException Rust 파서가 false 반환 시
     */
    @Override
    public BigDecimal parse(byte[] utf8Bytes) {
        long[] unscaled = OUT_UNSCALED.get();
        int[]  scale    = OUT_SCALE.get();

        boolean ok = parseDecimalNative(utf8Bytes, unscaled, scale);
        if (!ok) {
            throw new NumberFormatException(
                "decimal-rs: invalid decimal bytes (len=" + utf8Bytes.length + ")"
            );
        }

        // BigDecimal(long unscaledVal, int scale) — String 경로 완전 우회
        return BigDecimal.valueOf(unscaled[0], scale[0]);
    }

    /**
     * Rust 네이티브 메서드.
     *
     * <p>대응 Rust 함수: {@code Java_io_snapdecimal_jni_DecimalParserJni_parseDecimalNative}
     *
     * @param bytes       파싱할 UTF-8 바이트
     * @param outUnscaled [out] 결과 unscaled value (long[1])
     * @param outScale    [out] 결과 scale (int[1])
     * @return 파싱 성공 여부
     */
    private static native boolean parseDecimalNative(
        byte[] bytes,
        long[] outUnscaled,
        int[]  outScale
    );

    /**
     * JAR 내부 resources에서 플랫폼별 .so/.dll/.dylib 추출 후 로드.
     *
     * <p>패키징 구조:
     * <pre>
     *   resources/
     *     native/
     *       linux-x86_64/   libdecimal_rs_core.so
     *       macos-aarch64/  libdecimal_rs_core.dylib
     *       windows-x86_64/ decimal_rs_core.dll
     * </pre>
     */
    private static void loadNativeLibrary() {
        String os   = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        String platform;
        String libName;

        if (os.contains("linux")) {
            platform = "linux-" + normalizeArch(arch);
            libName  = "libdecimal_rs_core.so";
        } else if (os.contains("mac")) {
            platform = "macos-" + normalizeArch(arch);
            libName  = "libdecimal_rs_core.dylib";
        } else if (os.contains("win")) {
            platform = "windows-" + normalizeArch(arch);
            libName  = "decimal_rs_core.dll";
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + os);
        }

        String resourcePath = "/native/" + platform + "/" + libName;

        try (InputStream in = JniDecimalParser.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Native library not found in JAR: " + resourcePath);
            }
            File tmp = File.createTempFile("decimal_rs_core", libName.substring(libName.lastIndexOf('.')));
            tmp.deleteOnExit();
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load decimal-rs native library", e);
        }
    }

    private static String normalizeArch(String arch) {
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        if (arch.contains("amd64")   || arch.contains("x86_64")) return "x86_64";
        throw new UnsupportedOperationException("Unsupported arch: " + arch);
    }
}
