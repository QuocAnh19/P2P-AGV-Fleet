# Hệ thống điều phối đội xe tự hành (AGV Fleet) phi tập trung trên nền tảng Pure P2P Event Bus

Dự án hiện thực hóa **Tầng Giao thức Nhị phân (Binary Protocol Codec)** hiệu năng cao sử dụng **Netty Framework** cho mạng lưới P2P giữa các robot tự hành AGV (Autonomous Guided Vehicles).

---

## 1. Kiến trúc Khung Nhị phân (Binary Frame Specification)

Tổng chiều dài **Fixed Header** là đúng **36 Bytes**, theo chuẩn Big-Endian:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Magic (0xCAFE)          |    Opcode     |    Version    | (4 bytes)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                       Sequence ID (64-bit)                    + (8 bytes)
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                    Lamport Timestamp (64-bit)                 + (8 bytes)
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                    Sender Node ID (64-bit)                    + (8 bytes)
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Payload Length (32-bit)                  | (4 bytes)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      CRC32 Checksum (32-bit)                  | (4 bytes)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                       Payload Data (N Bytes)                  + (N bytes)
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Chi tiết các trường:
| Offset (Byte) | Độ dài (Byte) | Kiểu dữ liệu | Tên trường | Mô tả |
| :---: | :---: | :---: | :--- | :--- |
| **0** | 2 | `short` | **Magic Number** | Hằng số `0xCAFE` để nhận diện gói tin hợp lệ |
| **2** | 1 | `byte` | **Opcode** | Mã định danh loại thông điệp (P2P Discovery, Mutex, Task) |
| **3** | 1 | `byte` | **Version** | Phiên bản giao thức (mặc định `0x01`) |
| **4** | 8 | `long` | **Sequence ID** | Số thứ tự tăng dần định danh gói tin theo từng node |
| **12** | 8 | `long` | **Lamport Timestamp** | Đồng hồ logic Lamport phục vụ sắp xếp thứ tự sự kiện phân tán |
| **20** | 8 | `long` | **Sender Node ID** | Định danh 64-bit duy nhất của xe AGV gửi |
| **28** | 4 | `int` | **Payload Length** | Kích thước dữ liệu phần thân ($N \ge 0$) |
| **32** | 4 | `int` / `uint` | **CRC32 Checksum** | Checksum tính toán từ toàn bộ mảng byte của Payload |
| **36** | $N$ | `byte[]` | **Payload Data** | Dữ liệu nội dung (JSON / Protobuf / Binary payload) |

---

## 2. Bảng Danh mục Opcodes

| Mã Hex | Giá trị Byte | Enum Constant | Ý nghĩa trong hệ thống P2P AGV |
| :---: | :---: | :--- | :--- |
| `0x01` | `1` | `PEER_DISCOVERY` | Phát hiện và quảng bá sự hiện diện của node AGV mới trong mạng |
| `0x02` | `2` | `HEARTBEAT` | Kiểm tra nhịp tim (liveness check) định kỳ giữa các peer |
| `0x10` | `16` | `MUTEX_REQUEST` | Yêu cầu vào vùng tài nguyên găng (Thuật toán Ricart-Agrawala) |
| `0x11` | `17` | `MUTEX_REPLY` | Phản hồi chấp thuận yêu cầu vùng găng |
| `0x12` | `18` | `MUTEX_RELEASE` | Thông báo giải phóng vùng tài nguyên găng (ngã tư, trạm sạc) |
| `0x20` | `32` | `TASK_ANNOUNCE` | Phát lệnh công bố nhiệm vụ mới (Contract Net Protocol) |
| `0x21` | `33` | `TASK_BID` | Gửi gói thầu chào giá / chi phí thực hiện nhiệm vụ từ AGV |
| `0x22` | `34` | `TASK_AWARD` | Trao quyền thực hiện nhiệm vụ cho AGV thắng thầu |

---

## 3. Kiến trúc Mã nguồn (Clean Architecture)

```
D:/HK_V/P2P_AGV_Fleet/Project
├── pom.xml
├── src
│   ├── main
│   │   ├── java
│   │   │   └── com
│   │   │       └── agv
│   │   │           └── fleet
│   │   │               ├── common
│   │   │               │   └── protocol
│   │   │               │       ├── Opcode.java       # Enum mã lệnh + O(1) byte lookup
│   │   │               │       ├── AgvHeader.java    # Record bất biến (Fixed 36 bytes)
│   │   │               │       └── AgvMessage.java   # Domain model + Factory CRC32
│   │   │               └── network
│   │   │                   └── codec
│   │   │                       ├── AgvPacketEncoder.java # MessageToByteEncoder<AgvMessage>
│   │   │                       └── AgvPacketDecoder.java # LengthFieldBasedFrameDecoder
│   │   └── resources
│   │       └── logback.xml
│   └── test
│       └── java
│           └── com
│               └── agv
│                   └── fleet
│                       └── network
│                           └── codec
│                               └── AgvCodecTest.java # Bộ Test toàn diện trên EmbeddedChannel
```

### Đặc điểm thiết kế & Xử lý bộ nhớ tối ưu:
1. **Phân tách Ranh giới (Separation of Concerns):** Tầng Domain (`common.protocol`) hoàn toàn độc lập, bất biến (immutable), không dính chặt vào pipeline mạng của Netty.
2. **Quản lý Bộ nhớ Netty (Buffer Leak Prevention):**
   Trong `AgvPacketDecoder`, frame `ByteBuf` được giải phóng triệt để trong khối `finally { frame.release(); }`, đảm bảo không gây rò rỉ bộ nhớ Direct Memory (`ReferenceCounted`).
3. **Cấu hình `LengthFieldBasedFrameDecoder`:**
   - `maxFrameLength`: 10MB (`10 * 1024 * 1024`)
   - `lengthFieldOffset`: 28 bytes
   - `lengthFieldLength`: 4 bytes
   - `lengthAdjustment`: 4 bytes (bù đắp 4 bytes CRC32 nằm giữa `lengthField` và `payload`)
   - `initialBytesToStrip`: 0 (giữ nguyên toàn bộ 36 bytes header để xác thực)
4. **Kiểm tra tính toàn vẹn (Integrity Verification):**
   - Kiểm tra `Magic == 0xCAFE`
   - Phân giải Opcode hợp lệ
   - Tính toán CRC32 theo chuẩn IEEE 802.3 bằng `java.util.zip.CRC32` và so khớp với Checksum trong Header
   - Ném ngoại lệ `CorruptedFrameException` khi có bất kỳ sai lệch nào.

---

## 4. Bộ Kiểm thử (Unit Tests)

Bộ kiểm thử được viết bằng **JUnit 5 + AssertJ** kết hợp với **Netty EmbeddedChannel**:

| STT | Phương thức Test | Mục tiêu kiểm thử | Trạng thái |
| :---: | :--- | :--- | :---: |
| 1 | `testEncodeAndDecodeSingleMessage()` | Mã hóa và giải mã thông điệp hoàn chỉnh, kiểm tra 8 thuộc tính header và dữ liệu payload khớp 100%. | **PASSED** |
| 2 | `testStickyPacketsHandling()` | Mô phỏng hiện tượng dính gói TCP (ghép 3 gói tin liên tiếp vào 1 `ByteBuf`), đảm bảo Decoder tách chuẩn xác 3 `AgvMessage`. | **PASSED** |
| 3 | `testFragmentedPacketsHandling()` | Mô phỏng hiện tượng vỡ gói TCP (cắt 1 gói tin thành 3 mảnh byte rời rạc gửi vào channel), đảm bảo Decoder gom đủ mảnh mới trả về kết quả. | **PASSED** |
| 4 | `testCorruptedCrc32InPayload()` | Sửa 1 byte trong Payload dữ liệu, đảm bảo ném `CorruptedFrameException`. | **PASSED** |
| 5 | `testCorruptedCrc32InHeader()` | Sửa trường Checksum trong Header, đảm bảo ném `CorruptedFrameException`. | **PASSED** |
| 6 | `testInvalidMagicNumber()` | Thay đổi Magic Number sai chuẩn (ví dụ `0xDEAD`), đảm bảo ném `CorruptedFrameException`. | **PASSED** |
| 7 | `testInvalidOpcode()` | Gửi mã Opcode không định danh, đảm bảo ném `CorruptedFrameException`. | **PASSED** |
| 8 | `testEmptyPayloadMessage()` | Kiểm tra gói tin có Payload rỗng (36 bytes như Heartbeat), CRC32 = 0, xử lý an toàn. | **PASSED** |

### Chạy kiểm thử:
```bash
mvn clean test
```
