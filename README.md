# Hệ thống điều phối đội xe tự hành (AGV Fleet) phi tập trung trên nền tảng Pure P2P Event Bus

Dự án hiện thực hóa **Tầng Giao thức Nhị phân (Binary Protocol Codec)** và **Tầng Truyền tải P2P Mesh (Decentralized Mesh Transport Layer)** hiệu năng cao sử dụng **Netty Framework** cho mạng lưới robot tự hành AGV (Autonomous Guided Vehicles).

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
|                      Payload Length (32-bit)                  | (4 bytes, offset=28)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      CRC32 Checksum (32-bit)                  | (4 bytes, offset=32)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                       Payload Data (N Bytes)                  + (N bytes, offset=36)
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

## 3. Kiến trúc Tầng Truyền tải P2P Mesh (Pure P2P)

Hệ thống hoạt động hoàn toàn phi tập trung (Pure P2P), không sử dụng Broker hay Server trung tâm:
1. **UDP Multicast Discovery (`UdpPeerDiscovery`)**:
   - Tham gia multicast group `239.255.0.1:9876` qua Netty `NioDatagramChannel`.
   - Định kỳ (mỗi 1000ms) phát sóng `HELLO` mang (`nodeId`, `host`, `tcpPort`).
   - Tự động nhận diện mạng và thông báo cho `PeerManager`.
2. **Quy tắc Chống Trùng lặp Kết nối (Duplicate Connection Prevention)**:
   - Khi phát hiện peer mới: Chỉ node có `nodeId` **nhỏ hơn** chủ động tạo kết nối TCP outbound sang node có `nodeId` **lớn hơn**.
   - Node có ID lớn hơn đóng vai trò chờ kết nối inbound, loại bỏ hoàn toàn tình trạng race condition và kết nối kép giữa 2 xe.
3. **Xác thực Hai Chiều (Two-Way Handshake)**:
   - Ngay khi kênh TCP kích hoạt (`channelActive`), mỗi bên chủ động gửi gói `PEER_DISCOVERY (0x01)` chứa ID của mình để bên kia cập nhật định danh và lưu phiên `PeerSession`.
4. **Heartbeat & Phát hiện Lỗi (Node Failure Detection)**:
   - Định kỳ mỗi 500ms gửi gói tin `HEARTBEAT (0x02)` tới toàn bộ các peer.
   - Định kỳ kiểm tra: nếu `currentTime - lastHeartbeatTimestamp > 1500ms` (quá 3 chu kỳ rớt mạng), coi như node đã crash, tự động đóng socket, xóa khỏi `PeerManager` và kích hoạt sự kiện `onPeerDropped(nodeId)`.
5. **Đồng hồ Logic Lamport (Lamport Clock)**:
   - Mỗi tin nhắn gửi đi đều được đánh dấu nhãn thời gian logic để phục vụ giải thuật loại trừ tương hỗ (Ricart-Agrawala) và đấu thầu nhiệm vụ (Contract Net).

---

## 4. Cấu trúc Thư mục Dự án (Clean Architecture)

```
D:/HK_V/P2P_AGV_Fleet/Project
├── pom.xml
├── .gitignore
├── README.md
└── src
    ├── main
    │   ├── java
    │   │   └── com/agv/fleet
    │   │       ├── common/protocol
    │   │       │   ├── Opcode.java               # Enum 8 mã Opcode kèm O(1) lookup
    │   │       │   ├── AgvHeader.java            # Record bất biến (Đúng 36 Bytes Fixed Header)
    │   │       │   └── AgvMessage.java           # Domain Model + Factory tính CRC32 tự động
    │   │       ├── network
    │   │       │   ├── codec
    │   │       │   │   ├── AgvPacketEncoder.java # MessageToByteEncoder<AgvMessage>
    │   │       │   │   └── AgvPacketDecoder.java # LengthFieldBasedFrameDecoder (10MB, offset 28, adj 4)
    │   │       │   ├── discovery
    │   │       │   │   ├── PeerDiscoveryInfo.java# Record thông tin quảng bá UDP
    │   │       │   │   └── UdpPeerDiscovery.java # Netty DatagramChannel UDP Multicast
    │   │       │   └── mesh
    │   │       │       ├── PeerSession.java      # Quản lý phiên kết nối TCP và nhịp tim
    │   │       │       ├── PeerLifecycleListener.java # Callback onPeerConnected & onPeerDropped
    │   │       │       ├── MessageListener.java  # Callback xử lý gói tin ứng dụng
    │   │       │       ├── AgvMeshHandler.java   # Pipeline Handler: handshake, heartbeat, dispatch
    │   │       │       ├── AgvTcpServer.java     # Netty ServerBootstrap lắng nghe kết nối
    │   │       │       ├── AgvTcpClient.java     # Netty Bootstrap chủ động kết nối peer
    │   │       │       ├── PeerManager.java      # ConcurrentHashMap quản lý Full-Mesh & gửi tin
    │   │       │       └── HeartbeatController.java # Tác vụ gửi nhịp tim và timeout detector
    │   │       └── core
    │   │           └── AgvNode.java              # Lớp wrapper điều phối toàn bộ P2P stack
    │   └── resources
    │       └── logback.xml                       # Cấu hình log Logback
    └── test
        └── java
            └── com/agv/fleet
                └── network
                    ├── codec
                    │   └── AgvCodecTest.java     # 8 Unit Tests kiểm thử tầng Binary Codec
                    └── mesh
                        └── P2PMeshIntegrationTest.java # 3 Integration Tests đa node Full-Mesh
```

---

## 5. Kết quả Kiểm thử (Unit & Integration Tests)

Toàn bộ **11 bài kiểm thử** đều vượt qua 100%:

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.agv.fleet.network.codec.AgvCodecTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.430 s -- in com.agv.fleet.network.codec.AgvCodecTest
[INFO] Running com.agv.fleet.network.mesh.P2PMeshIntegrationTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.857 s -- in com.agv.fleet.network.mesh.P2PMeshIntegrationTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

### Chi tiết các kịch bản kiểm thử:
| STT | File Test | Phương thức Test | Mục tiêu kiểm thử | Trạng thái |
| :---: | :--- | :--- | :--- | :---: |
| 1 | `AgvCodecTest` | `testEncodeAndDecodeSingleMessage()` | Mã hóa và giải mã thông điệp hoàn chỉnh, 8 trường header khớp 100%. | **PASSED** |
| 2 | `AgvCodecTest` | `testStickyPacketsHandling()` | Mô phỏng dính gói TCP (3 frame trong 1 ByteBuf), giải mã ra 3 tin độc lập. | **PASSED** |
| 3 | `AgvCodecTest` | `testFragmentedPacketsHandling()` | Mô phỏng vỡ gói TCP (cắt 1 frame thành 3 mảnh byte rời rạc), ghép lại hoàn chỉnh. | **PASSED** |
| 4 | `AgvCodecTest` | `testCorruptedCrc32InPayload()` | Sửa 1 byte Payload, ném `CorruptedFrameException`. | **PASSED** |
| 5 | `AgvCodecTest` | `testCorruptedCrc32InHeader()` | Sửa Checksum trong Header, ném `CorruptedFrameException`. | **PASSED** |
| 6 | `AgvCodecTest` | `testInvalidMagicNumber()` | Gửi Magic sai lệch, ném `CorruptedFrameException`. | **PASSED** |
| 7 | `AgvCodecTest` | `testInvalidOpcode()` | Gửi Opcode không xác định, ném `CorruptedFrameException`. | **PASSED** |
| 8 | `AgvCodecTest` | `testEmptyPayloadMessage()` | Frame 36 bytes không payload (Heartbeat), CRC32 = 0, giải mã chuẩn. | **PASSED** |
| 9 | `P2PMeshIntegrationTest` | `testThreeNodesFullMeshDiscoveryAndConnect()` | Khởi động 3 node độc lập, tự động khám phá qua UDP và kết nối Full-Mesh. | **PASSED** |
| 10 | `P2PMeshIntegrationTest` | `testBroadcastMessage()` | Node 1 broadcast thông điệp task, Node 2 & 3 nhận được chính xác nội dung. | **PASSED** |
| 11 | `P2PMeshIntegrationTest` | `testNodeCrashRecovery()` | Tắt đột ngột Node 3, Node 1 & 2 phát hiện timeout nhịp tim và xóa khỏi danh bạ. | **PASSED** |

---

## 6. Hướng dẫn Chạy Thử nghiệm

Chạy toàn bộ test suite từ terminal:
```bash
mvn clean test
```
