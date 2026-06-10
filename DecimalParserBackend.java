package io.snapdecimal.core;
 
import java.math.BigDecimal;
 
/**
 * 백엔드 구현체 공통 인터페이스.
 * JNI 구현({@code JniDecimalParser})과 Panama 구현({@code PanamaDecimalParser}) 모두 이 인터페이스를 구현합니다.
 */
public interface DecimalParserBackend {
    BigDecimal parse(byte[] utf8Bytes);
}
