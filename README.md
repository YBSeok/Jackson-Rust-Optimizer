# Jackson-Rust Optimizer

## 소개

이 프로젝트는 Jackson JSON 파서의 성능 병목 구간을 Rust로 최적화하기 위한 실험 프로젝트입니다.

Java 애플리케이션에서 Jackson의 핵심 파싱 로직을 유지하면서, 성능이 중요한 일부 구간(BigDecimal 파싱, JSON 토크나이징 등)을 Rust 네이티브 라이브러리로 대체하여 처리량(Throughput) 향상과 GC 부담 감소를 목표로 합니다.

JNI(Java Native Interface) 또는 Project Panama(FFM API)를 통해 Java와 Rust를 연결하며, SIMD 기반 고성능 파싱을 활용할 수 있습니다.

---

## 주요 기능

### 기능 1. 고속 BigDecimal 파싱

* String 중간 객체 생성을 제거
* Rust에서 직접 숫자 바이트를 파싱
* `unscaledValue`와 `scale` 정보를 Java로 반환
* GC Pressure 감소

### 기능 2. SIMD 기반 JSON 토크나이징

* Rust의 `simd-json` 활용
* AVX2 SIMD 명령어 기반 병렬 처리
* Jackson 기본 토크나이저 대비 높은 처리 성능 기대

### 기능 3. JNI 및 FFM API 지원

* JNI(Java Native Interface) 연동
* JDK 22+ Foreign Function & Memory API 지원
* 낮은 네이티브 호출 오버헤드

### 기능 4. Zero-Copy 데이터 처리

* ByteBuffer 기반 Off-Heap 메모리 활용
* 불필요한 메모리 복사 최소화
* 이벤트 소싱 및 로그 리플레이 최적화

### 기능 5. 벤치마크 지원

* JMH 기반 성능 측정
* Java 구현과 Rust 구현 비교
* Throughput, Allocation, GC 측정

---

## 아키텍처

```text
Java Application
       │
       ▼
Jackson Parser
       │
       ▼
JNI / FFM API
       │
       ▼
Rust Native Library
       │
       ├─ Fast Decimal Parser
       ├─ SIMD JSON Tokenizer
       └─ Zero-Copy Processing
```

---

## 사용 방법

### 다운로드

```bash
git clone https://github.com/your-org/jackson-rust-optimizer.git

cd jackson-rust-optimizer
```

### Rust 라이브러리 빌드

```bash
cargo build --release
```

생성 결과:

```text
Linux   : libjackson_rust.so
macOS   : libjackson_rust.dylib
Windows : jackson_rust.dll
```

### Java 프로젝트 빌드

```bash
./gradlew build
```

### 실행

```bash
./gradlew run
```

또는

```bash
java -jar build/libs/app.jar
```

---

## 성능 목표

| 항목            | 기존 Jackson   | Rust 최적화     |
| ------------- | ------------ | ------------ |
| BigDecimal 파싱 | String 생성 필요 | String 생성 제거 |
| JSON 토크나이징    | Byte-by-Byte | SIMD 병렬 처리   |
| 객체 할당         | 높음           | 최소화          |
| GC 영향         | 높음           | 낮음           |

---

## 보안 고려사항

* Rust 레이어에서는 Java 클래스 로딩이 발생하지 않음
* Jackson의 Polymorphic Deserialization 관련 취약점 영향 최소화
* JNI/FFM 경계에서 버퍼 검증 수행
* Null Pointer 및 Buffer Overflow 방어 로직 포함

---

## 향후 계획

* Jackson Core 직접 통합
* SIMD 최적화 확장
* ARM NEON 지원
* Project Panama 완전 지원
* GraalVM Native Image 호환성 검증

---

## 라이선스

MIT License

Copyright (c) 2026
