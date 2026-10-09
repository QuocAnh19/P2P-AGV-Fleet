# Hệ thống điều phối đội xe tự hành (AGV Fleet) phi tập trung trên nền tảng Pure P2P Event Bus

Dự án hiện thực hóa trọn gói:
1. **Tầng Giao thức Nhị phân (Binary Protocol Codec)** hiệu năng cao sử dụng **Netty Framework**.
2. **Tầng Truyền tải P2P Mesh (Decentralized Mesh Transport Layer)** tự động khám phá và duy trì mạng lưới.
3. **Tầng Thuật toán Phân tán (Distributed Consensus Layer)**: Đồng hồ logic Lamport và Thuật toán Ricart-Agrawala giải quyết tranh chấp ngã tư (Distributed Mutual Exclusion).
4. **Tầng Đấu thầu Công việc Tự động (Contract Net Protocol - CNP Engine)**: Phân phối và đấu thầu nhiệm vụ vận chuyển hàng tự động, thích ứng theo khoảng cách và mức pin.

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

## 3. Tầng Thuật toán Phân tán (Distributed Consensus)

### 3.1. Đồng hồ Logic Lamport (`LamportClock`)
- Cung cấp trật tự một phần (partial ordering) cho toàn bộ sự kiện trong hệ thống:
  - Sự kiện nội bộ hoặc trước khi phát tin: $L = L + 1$ (`tick()`).
  - Khi nhận gói tin từ mạng: $L = \max(L, L_{received}) + 1$ (`updateAndGet()`).

### 3.2. Thuật toán Loại trừ Tương hỗ Ricart-Agrawala (`RicartAgrawalaEngine`)
Giải quyết tranh chấp ngã tư (`cellId`) không có điểm nghẽn trung tâm:
1. **Trạng thái (`MutexState`)**:
   - `RELEASED`: Không có nhu cầu đi vào ngã tư -> Sẵn sàng phản hồi `MUTEX_REPLY` ngay cho bất kỳ ai hỏi.
   - `WANTED`: Đang yêu cầu vào ngã tư -> Đợi thu thập đủ `MUTEX_REPLY` từ tất cả các peer đang active.
   - `HELD`: Đang độc quyền chiếm giữ ngã tư -> Trì hoãn mọi yêu cầu xin vào ngã tư này (`deferredQueue`).
2. **Quy tắc Phân định Ưu tiên (Conflict & Deadlock Resolution)**:
   - Khi Node $A$ (đang `WANTED`) nhận yêu cầu từ Node $B$:
     - Nếu $A$ có ưu tiên cao hơn: $(L_A < L_B)$ hoặc $(L_A = L_B \text{ và } ID_A < ID_B)$: Node $A$ hoãn trả lời (đưa $B$ vào `deferredQueue`).
     - Ngược lại: Node $A$ gửi ngay `MUTEX_REPLY` cho $B$.
3. **Giải phóng Ngã tư (`releaseIntersection`)**:
   - Chuyển trạng thái sang `RELEASED`.
   - Gửi ngay `MUTEX_REPLY` cho tất cả các xe đang đợi trong `deferredQueue`.
   - Broadcast thông báo `MUTEX_RELEASE` cho toàn mạng.
4. **Tự phục hồi khi Peer sập (`onPeerDropped`)**:
   - Khi một xe bị crash (phát hiện qua Heartbeat timeout), xe đó tự động bị xóa khỏi `pendingReplies`. Xe đang đợi lập tức được cấp quyền `HELD` an toàn mà không bị deadlock vô tận.

### 3.3. Tầng Đấu thầu Phân tán Contract Net Protocol (`CnpEngine`)
Phân bổ nhiệm vụ vận chuyển hàng hóa tự động phi tập trung (Multi-Agent System):
1. **Hàm Mục tiêu Chi phí (Cost Function)**:
   $$C = 0.5 \times d_{\text{pickup}} + 0.3 \times (100 - B) + 0.2 \times W_{\text{maintenance}}$$
   - $d_{\text{pickup}} = \sqrt{(x_{\text{pickup}} - x_{\text{agv}})^2 + (y_{\text{pickup}} - y_{\text{agv}})^2}$: Khoảng cách di chuyển tới điểm nhận hàng.
   - $B \in [0.0, 100.0]$: Mức pin hiện tại của AGV (%).
   - $W_{\text{maintenance}}$: Trọng số bảo trì kỹ thuật của xe.
2. **Quy tắc An toàn Pin (Battery Safety Rule)**:
   - Nếu $B \le 15.0\%$ (Ngưỡng `LOW_BATTERY_THRESHOLD`), AGV kiên quyết từ chối nộp thầu và tự động chuyển trạng thái sang `LOW_BATTERY_CHARGING` để bảo vệ pin.
3. **Quy tắc Trạng thái Tác tử (`AgentState`)**:
   - Chỉ AGV ở trạng thái rảnh rỗi (`IDLE`) mới tham gia nộp thầu (`TASK_BID`).
   - Sau khi nộp thầu, AGV chuyển sang `BIDDING`.
   - Nếu trúng thầu (`TASK_AWARD`), AGV chuyển sang `BUSY` để thực thi nhiệm vụ.
   - Nếu trượt thầu, AGV tự động hoàn nguyên về `IDLE`.
4. **Khả năng Chịu lỗi (Fault-Tolerance)**:
   - **Thầu phụ (Contractor) sập giữa chừng**: Node quản lý phát hiện mất kết nối qua Heartbeat, tự động hủy phân bổ và tái phát thầu (`re-announce`) để không bị thất lạc đơn hàng.
   - **Chủ thầu (Manager) sập**: Các xe đang trong trạng thái `BIDDING` tự động hủy gói thầu và hoàn nguyên về `IDLE`.

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
    │   │       │   ├── AgvMessage.java           # Domain Model + Factory tính CRC32 tự động
    │   │       │   └── payload
    │   │       │       ├── MutexRequestPayload.java # Payload nhị phân xin ngã tư
    │   │       │       ├── MutexReplyPayload.java   # Payload nhị phân chấp thuận
    │   │       │       ├── MutexReleasePayload.java # Payload nhị phân giải phóng
    │   │       │       ├── TaskAnnouncePayload.java # Payload nhị phân phát thầu (0x20)
    │   │       │       ├── TaskBidPayload.java      # Payload nhị phân nộp thầu (0x21)
    │   │       │       └── TaskAwardPayload.java    # Payload nhị phân trao thầu (0x22)
    │   │       ├── distributed
    │   │       │   ├── clock
    │   │       │   │   └── LamportClock.java     # Đồng hồ logic Lamport thread-safe
    │   │       │   ├── mutex
    │   │       │   │   ├── MutexState.java       # RELEASED, WANTED, HELD
    │   │       │   │   ├── CellMutex.java        # State container cho từng ô ngã tư
    │   │       │   │   └── RicartAgrawalaEngine.java # Máy thuật toán Ricart-Agrawala
    │   │       │   └── auction
    │   │       │       ├── AuctionSession.java   # Phiên đấu thầu theo dõi bids và timeout
    │   │       │       ├── TaskAssignmentListener.java # Listener sự kiện trúng thầu
    │   │       │       └── CnpEngine.java        # Bộ máy đấu thầu Contract Net Protocol
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
    │   │       │       ├── AgvMeshHandler.java   # Pipeline Handler: handshake, heartbeat, mutex & cnp dispatch
    │   │       │       ├── AgvTcpServer.java     # Netty ServerBootstrap lắng nghe kết nối
    │   │       │       ├── AgvTcpClient.java     # Netty Bootstrap chủ động kết nối peer
    │   │       │       ├── PeerManager.java      # ConcurrentHashMap quản lý Full-Mesh & gửi tin
    │   │       │       └── HeartbeatController.java # Tác vụ gửi nhịp tim và timeout detector
    │   │       └── core
    │   │           ├── AgvNode.java              # Lớp wrapper điều phối toàn bộ P2P stack
    │   │           └── agent
    │   │               ├── AgentState.java       # IDLE, BIDDING, BUSY, LOW_BATTERY_CHARGING
    │   │               ├── AgvBattery.java       # Thread-safe battery management (15% threshold)
    │   │               ├── Position2D.java       # Tọa độ 2D và tính toán khoảng cách không gian
    │   │               └── Task.java             # Quản lý vòng đời đơn vận chuyển hàng
    │   └── resources
    │       └── logback.xml                       # Cấu hình log Logback
    └── test
        └── java
            └── com/agv/fleet
                ├── common/protocol/payload
                │   ├── MutexPayloadTest.java     # 3 Tests serialization mutex payload
                │   └── CnpPayloadTest.java       # 4 Tests serialization CNP payload
                ├── distributed
                │   ├── clock
                │   │   └── LamportClockTest.java # 3 Tests đồng hồ Lamport
                │   ├── mutex
                │   │   └── RicartAgrawalaTest.java# 3 Integration Tests Ricart-Agrawala
                │   └── auction
                │       └── CnpAuctionTest.java   # 4 Integration Tests đấu thầu CNP
                └── network
                    ├── codec
                    │   └── AgvCodecTest.java     # 8 Unit Tests kiểm thử tầng Binary Codec
                    └── mesh
                        └── P2PMeshIntegrationTest.java # 3 Integration Tests đa node Full-Mesh
```

---

## 5. Kết quả Kiểm thử (Unit & Integration Tests)

Toàn bộ **28 bài kiểm thử** đều vượt qua 100%:

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.agv.fleet.common.protocol.payload.CnpPayloadTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.052 s
[INFO] Running com.agv.fleet.common.protocol.payload.MutexPayloadTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s
[INFO] Running com.agv.fleet.distributed.auction.CnpAuctionTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.115 s
[INFO] Running com.agv.fleet.distributed.clock.LamportClockTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.046 s
[INFO] Running com.agv.fleet.distributed.mutex.RicartAgrawalaTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.488 s
[INFO] Running com.agv.fleet.network.codec.AgvCodecTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.441 s
[INFO] Running com.agv.fleet.network.mesh.P2PMeshIntegrationTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.627 s
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
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
| 12 | `LamportClockTest` | `testTick()` | Kiểm tra đồng hồ tăng tuần tự theo sự kiện nội bộ. | **PASSED** |
| 13 | `LamportClockTest` | `testUpdateWithHigherTimestamp()` | Cập nhật $L = \max(L, L_{recv}) + 1$ với timestamp cao hơn. | **PASSED** |
| 14 | `LamportClockTest` | `testUpdateWithLowerTimestamp()` | Tiến đồng hồ ngay cả khi nhận timestamp thấp hơn. | **PASSED** |
| 15 | `MutexPayloadTest` | `testMutexRequestPayloadRoundTrip()` | Kiểm tra serialize/deserialize nhị phân `MutexRequestPayload`. | **PASSED** |
| 16 | `MutexPayloadTest` | `testMutexReplyPayloadRoundTrip()` | Kiểm tra serialize/deserialize nhị phân `MutexReplyPayload`. | **PASSED** |
| 17 | `MutexPayloadTest` | `testMutexReleasePayloadRoundTrip()` | Kiểm tra serialize/deserialize nhị phân `MutexReleasePayload`. | **PASSED** |
| 18 | `RicartAgrawalaTest` | `testSingleNodeImmediateAcquire()` | Mạng 1 node xin ngã tư nhận ngay quyền `HELD` không bị block. | **PASSED** |
| 19 | `RicartAgrawalaTest` | `testTwoNodesHeadOnCollisionDeadlockResolution()` | 2 node cùng xin ngã tư: Node ưu tiên hơn vào trước, node kia bị block; khi node 1 release, node 2 được cấp quyền; 0 deadlock. | **PASSED** |
| 20 | `RicartAgrawalaTest` | `testAcquireWithCrashingPeer()` | Node 2 crash khi Node 1 đang đợi: Node 1 tự động phát hiện và thăng cấp `HELD`. | **PASSED** |
| 21 | `CnpPayloadTest` | `testTaskAnnouncePayloadRoundTrip()` | Kiểm tra serialize/deserialize nhị phân `TaskAnnouncePayload` (Opcode 0x20). | **PASSED** |
| 22 | `CnpPayloadTest` | `testTaskBidPayloadRoundTrip()` | Kiểm tra serialize/deserialize nhị phân `TaskBidPayload` (Opcode 0x21). | **PASSED** |
| 23 | `CnpPayloadTest` | `testTaskAwardPayloadRoundTrip()` | Kiểm tra serialize/deserialize nhị phân `TaskAwardPayload` (Opcode 0x22). | **PASSED** |
| 24 | `CnpPayloadTest` | `testPayloadValidation()` | Kiểm tra validation ném ngoại lệ khi taskId rỗng hoặc null. | **PASSED** |
| 25 | `CnpAuctionTest` | `testAdaptiveAuctionBatteryPenalty()` | Xe pin yếu (10% $\le$ 15%) từ chối thầu chuyển sang `LOW_BATTERY_CHARGING`; xe ở xa (8m) pin khỏe (90%) trúng thầu. | **PASSED** |
| 26 | `CnpAuctionTest` | `testMultipleBiddersSelectsMinCost()` | 3 xe nộp thầu cạnh tranh: Xe có chi phí tối ưu nhất (4.2 điểm) thắng thầu; các xe thua tự động hoàn nguyên `IDLE`. | **PASSED** |
| 27 | `CnpAuctionTest` | `testBusyAgvIgnoresAuction()` | Xe đang bận (`BUSY`) bỏ qua bản tin phát thầu, không tham gia đấu thầu. | **PASSED** |
| 28 | `CnpAuctionTest` | `testContractorCrashReAnnouncesTask()` | Xe thầu sập đột ngột khi đang giữ việc: Chủ thầu phát hiện và tự động tái phát thầu (`re-announce`). | **PASSED** |

---

## 6. Hướng dẫn Chạy Kiểm thử

```bash
mvn clean test
```
