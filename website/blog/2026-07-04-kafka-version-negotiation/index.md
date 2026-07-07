---
title: "溝通當下的版本選擇：Kafka 如何在通訊當下挑 wire protocol 版本"
slug: kafka-version-negotiation
authors: [unknowntpo]
tags: [Kafka, Protocol, Versioning]
---

> **幾萬個節點之上的版本控制 · 第三場**　｜　長壽的 client、滾動升級的 broker，如何協商出共同版本
>
> 本篇專注在**溝通當下到底選哪個版本**：一條連線怎麼決定要講第幾版的 wire protocol、`metadata.version`（MV）在這裡扮演什麼角色、以及協商不出版本時會看到什麼錯誤。
>
> 整個主題拆成系列，相鄰兩場各自成篇；本篇只在需要時做前情提要、不重講：
>
> - 《Kafka 叢集版本定義與 KIP-1170》：release version（上限與規則）vs `metadata.version`（叢集當前啟用範圍）、MV 如何決定 record schema、feature gate 與啟動檢查。
> - 《運行時的版本升降》（明彥那場）：`kafka-features` 的 finalize 驗證、feature↔MV 依賴與降級規則、fenced broker 擋升級等「升降當下」的行為。
>
> 正文聚焦「機制」；對應的 source code 片段與 file:line 收在文末 [附錄 A：原始碼對照](#appendix-a)，想深入的人再翻。commit 基準 trunk `b7b1c0a8`。

---

## Part 1 — 版本為何對不齊，又是怎麼決定的

### 1. 動機：為什麼版本天生對不齊，只能連線當下協商

大多數人談「Kafka 版本」時，腦中只有一個數字（例如 3.6、4.1），並隱含一個假設：**client 與 broker 同版、一起升級**——「一個版本打天下」。這個假設在單機或測試環境成立，但在生產叢集會被兩個現實打破。

第一，**client 很長壽**。broker 由平台團隊維護、會跟著升級；但 client 是嵌在各個應用程式裡的函式庫——某個多年前的 batch job、某個沒人敢動的 legacy service，可能到今天還抱著很舊的 kafka-client 在跑。要全公司應用同一天升 client，基本不可能。這不是隨口說的：Apache Kafka 從 0.8.0（2013）到 3.x，整整九年保留了「每一個」protocol API 版本，就因為總有舊 client 還在連；直到 4.0（[KIP-896](https://cwiki.apache.org/confluence/display/KAFKA/KIP-896%3A+Remove+old+client+protocol+API+versions+in+Kafka+4.0)）才把 baseline 提到約 2.1（2018）。

第二，**broker 逐台滾動升級，過程必然新舊並存**。升 broker 是一台一台來：關一台、換 binary、起來、再下一台。過程中叢集必然「新舊 broker 混在一起」，client 也同時連到新的和舊的 broker。要不停機，就不能要求全叢集同版。

因果鏈收攏成一條：任一時刻，不同節點的 binary 能力必然不同 → 要不停機就不能鎖全叢集同一版 → 版本無法事先對齊，只能在「連線當下」由雙方決定。這就是本場主題的由來。後面會介紹三種通訊角色（client↔broker、broker↔controller、broker↔broker），先說清楚：那是「版本選擇發生的地方」，不是版本對不齊的原因——對不齊的原因就是上面兩個現實。

先預告這個選擇的帳單：把版本拆到每支 API 各自一組 `[min, max]` 區間，協定面積、相容性測試、非 Java client 的實作負擔，全都按 API 數放大——MongoDB 與 PostgreSQL 面對同一個問題，選的是只維護一個全域協定版本號，把演進粒度讓給實作簡單。Kafka 買到的是單支 API 獨立演進、不必整包升版；這個成本後來大到需要 [KIP-482](https://cwiki.apache.org/confluence/display/KAFKA/KIP-482%3A+The+Kafka+Protocol+should+Support+Optional+Tagged+Fields) 的 flexible versions 來止血——一旦某支 API 進入 flexible version，後續的 optional tagged fields 就不必再 bump 版本。評價：對一個 client 由眾多第三方各自實作、API 數十支的生態，這筆帳划算；但它只在這種生態下划算，不是通用解。

### 2. 術語：講「版本」時，指的是哪一層？

進入機制前先釐清術語。日常說的「版本」其實混了三種 scope 不同、變動時機也不同的「版本」：

```text
release version        我這台裝了哪版 binary       per-node（ops 換 binary，逐台滾動）
metadata.version       叢集 finalized 的 feature level    cluster-wide（admin 手動 finalize，刻意跟換 binary 脫鉤）
wire protocol API ver  這條連線實際講第幾版        per-connection（runtime 每條連線各自決定）
```

- **release version**：這台節點裝了哪一版 binary。per-node，由維運換 binary、逐台滾動。
- **`metadata.version`**：叢集 finalized 的 feature level。所謂 finalize，指管理員手動宣告全叢集一致採用的 feature level；怎麼宣告是第一場《版本定義》的主題，本場只需要知道它是叢集共識的一個值。cluster-wide，刻意跟換 binary 脫鉤——升了 binary 不代表 MV 就跟著升。
- **wire protocol API version**：這條連線實際講第幾版。per-connection，runtime 每條連線各自決定。

這三層是三個獨立的軸，不會自動一起變：可以升了 binary（release version）卻還沒 finalize `metadata.version`；也可以兩個 broker binary 同版，卻因連線當下選出不同 wire protocol version 而行為不同。本場的主角是第三層——wire protocol API version。

### 3. 架構 (a)：三種通訊角色；要協商，先送 `ApiVersionsRequest`

wire 版本的資訊來源是一次查詢：**連線建立後，發起端先送一支 `ApiVersionsRequest`，查詢對方「你每支 API 支援哪個版本區間？」**，對方回覆自己每支 API 的 `[min, max]` 範圍。這個機制由 [KIP-35](https://cwiki.apache.org/confluence/display/KAFKA/KIP-35+-+Retrieving+protocol+version) 引入，是後續一切版本選擇的前提。

這個查詢有代價：每條新連線多付一次 round trip——`NetworkClient` 會把新連線排進 `ApiVersionsRequest` 流程、收到回覆後才視為 ready（`NetworkClient.java:1106` / `:1122`）。換到的是之後每支 request 都不必再猜版本：一次性成本，長期攤提。

Kafka 叢集裡有三種通訊角色（各列的 RPC 僅代表性、非窮舉）。絕大多數路徑連線後都先做這個查詢；唯一不查詢的是 **partition replication**（follower→leader，元件是 replica fetcher）那條流程——原因見下一節：

```text
client ↔ broker       讀寫資料、查 metadata        Produce、Fetch、Metadata…
broker ↔ controller   註冊、心跳、轉發 admin 請求   BrokerRegistration、BrokerHeartbeat…
broker ↔ broker       partition replication / txn markers   Fetch、ListOffsets / WriteTxnMarkers…
```

注意 broker↔broker 不是鐵板一塊：**partition replication**（follower→leader）不查詢，但同是 broker↔broker 的 **transaction markers（`WriteTxnMarkers`）仍照 KIP-35 協商**（`discoverBrokerVersions=true`）。所以「不查詢」修飾的是 partition replication 這條特定流程，不是整個 broker↔broker 角色。

查詢只解決「知道對方會講什麼」；拿到區間之後，**最終版本怎麼定，各路徑的答案並不相同**——這是下一節的主題。

### 4. 架構 (b)：查詢之後，最終版本誰說了算？

先看各路徑各自的現象：

- **client ↔ broker：協商。** 一般 producer / consumer / admin API，取雙方區間交集的最高版本（`NodeApiVersions.latestUsableVersion`）。`MetadataVersion` 的 javadoc 也寫明：「when communicating with clients, the client decides on the API version.」
- **broker ↔ controller：也是協商。** broker 對 controller 送 `BrokerHeartbeat`、`BrokerRegistration` 等 request 時，扮演的是 client 角色，同樣取交集最高版，不由 MV 決定。
- **partition replication（follower→leader）：不協商。** 這條由 replica fetcher 走的流程，發起端不查詢對方版本（`discoverBrokerVersions=false`），版本改由 finalized MV 事先決定。這條連線上依序發三支不協商 RPC——`OffsetsForLeaderEpoch`（對齊）、`ListOffsets`（定位）、`Fetch`（抓資料）；代表性的 `Fetch` 版本由 `fetchRequestVersion(MV)` 直接決定，leader 不支援即失敗，沒有退讓空間。（同是 broker↔broker 的 transaction markers 則仍協商。）

三種現象收斂到同一個機制：**決定權在「組出這支 request 的程式碼」宣告的允許版本範圍**。送出端建 request 時，`AbstractRequest.Builder` 帶一個 `[oldestAllowedVersion, latestAllowedVersion]`；底層送出前會把它跟對方 `ApiVersions` 廣播的範圍取交集：

| Builder 給的範圍 | 效果 |
| --- | --- |
| 全範圍 `[oldest, latest]` | `ApiVersions` 協商，挑交集最高版 |
| pin 成 `[MV, MV]` | MV 決定（replication 路徑不查詢、直接照該版本送；不支援即 `UnsupportedVersionException`） |
| pin 成固定常數 | 寫死，不看 MV 也無協商空間 |

client↔broker 與 broker↔controller 的 Builder 給全範圍，落在第一格；複製面的 `Fetch` 被 pin 成 `[MV, MV]`，落在第二格。複製面另外兩支 RPC 的分工更細（`ListOffsets` 以 MV 為上限、`OffsetsForLeaderEpoch` 寫死常數），屬進階細節，完整對照表收在[附錄 A3](#a3)；主訊息只需要一句：**複製面的 `Fetch` 由 MV 決定，其餘都是協商。**

#### 為什麼複製面用 MV、其餘協商？

受眾看到「複製面不協商」的第一個問題必然是：為什麼它不像其他連線一樣協商就好？直接的答案是：**協商能讓 broker 讀得懂彼此送來的 request，但「讀得懂」不等於「跨版本行為正確」。** 例：[KIP-903](https://cwiki.apache.org/confluence/display/KAFKA/KIP-903%3A+Replicas+with+stale+broker+epoch+should+not+be+allowed+to+join+the+ISR) 在 `Fetch` v15 加入 `ReplicaState`（帶 broker epoch）來擋住舊 epoch 的 follower 被加進 ISR——leader 就算讀得懂舊版 `Fetch`（協商也降得到舊版），少了那個欄位就擋不住這件事。所以複製面要的不是「兩端能通」，而是「全叢集一致地啟用同一版行為」；finalized MV 提供的正是 **operator 可控、與滾 binary 脫鉤的原子切換**——一次把全叢集切到指定版本，這也是升級要分兩階段的由來。其餘連線是點對點，各自挑最好的版本即可。

再往下想一層，「為什麼這樣分」有三個理由（設計推論，但每一條都對得上已驗證的機制）：

1. **Bootstrap 的雞生蛋。** finalized MV 本身存在 metadata log 裡；broker 是靠「跟 controller 抓 metadata log」才知道 MV。若抓 log 的那支 `Fetch` 版本要由 MV 決定，就會循環——要知道 MV 得先抓 log，要抓 log 又得先知道 MV。所以啟動期的 RPC（registration、抓 metadata log 的 `Fetch`）不能被 MV gate，只能用自足的 `ApiVersions` 協商。鐵證：KRaft 抓 metadata log 的 `Fetch` 走全範圍協商、不看 MV（見[附錄 A4](#a4)）。
2. **控制面與資料面對「一致性」的需求不同。** broker↔controller 是點對點（broker 對現任 controller），每條連線各自挑最好版本即可，不需要全叢集一致。複製資料面則需要所有 follower↔leader 講同一版——MV 就是那個「集中、一次切換」的開關。
3. **不拿權威發的值去 gate 通往權威的通道。** controller 是 MV 的來源；用「它發的 MV」去決定「連到它的那條 RPC」的版本，邏輯上不成立。

另補一個常見誤解的修正：這**不是「broker 加入叢集前協商、加入後改用 MV」的階段切換**。`BrokerHeartbeat` 每隔幾秒送一次、貫穿 broker 整個生命週期，從頭到尾都是協商。真正的區分是永久按 RPC 角色分：控制面永遠協商、複製資料面永遠由 MV 決定。

#### MV 集中決定的帳單，與一個反事實

這個集中決定不是免費的，帳單有三筆：**升級從一步變兩步**（先滾動換 binary、再手動 finalize）；**多一個會被忘記的人工步驟**——忘了 finalize，replication 就一直講舊版（`fetchRequestVersion` 的門檻表停在舊值，upgrade guide 也明確把 finalize 列為 binary 換完之後的獨立步驟）；以及**沒有退讓空間**——leader 不支援 MV 指定的版本就直接失敗，不像協商還能降版。要說明的是，這個「兩階段」是 finalized MV 治理的通性、不是 replication 專屬：任何由 MV gate 的能力（含 metadata log 的 record 版本）都循同一條「先滾 binary、再 finalize」的節奏。

要理解這筆帳為什麼值得付，看反事實最快：PostgreSQL 的實體複製（physical / streaming replication）傳的是 raw WAL bytes，而 **WAL 格式跨大版不相容**，複製能力於是硬鎖在同一個 major——所以 HA replica **不能就地跨 major 滾動升級**，只能 `pg_upgrade`（快但要停機）。要做近零停機的大版升級，得改搭 **logical replication**（decode WAL 成 row changes、獨立於實體格式，可跨 major）——blue/green、另建新 major 叢集再 failover，但那是外掛（bolt-on）而非複製層內建，且有 DDL / sequence 不複製等限制。Kafka 的兩階段升級再麻煩，換到的正是 PostgreSQL 實體複製給不了的那件事：**對複製層本身做線上滾動升級**。我認為這是全套設計裡最站得住的取捨。（MongoDB 的 `featureCompatibilityVersion` 與 MV 是同一套思路；三家對照見[附錄 C](#appendix-c)。）

最後一句防混淆：feature（如 `group.version`、`share.version`）**不決定 RPC 版本**——只有 MV 對複製面的 `Fetch` / `ListOffsets` 這麼做；feature 與 RPC 版本是兩條正交的軸（詳見[附錄 A5](#a5)）。

### 5. 以 `Fetch` 為例：同一顆 broker 同時講兩個版本

把上一節的架構落到一支具體的 RPC。同一支 `Fetch` API 有兩種身分，分別走 client↔broker 與 broker↔broker 兩條選版路徑：

```text
consumer fetch   Kafka 2.4 client（validVersions 0-11）  對 4.1 broker 取交集 → Fetch v11   ← 協商
replica  fetch   follower                                 版本 = fetchRequestVersion(MV) → v17  ← 由 finalized MV 決定
```

於是同一顆 4.1 broker，會**同時**對 Kafka 2.4 老 client 協商出 Fetch v11（其 `validVersions` 是 0-11，取交集的最高共同版本）、對 follower 用 finalized MV 決定的 v17——**同一個 release，同時存在多個 wire 版本**。一個版本號根本表達不了這件事。

順帶評價這個 dual-role 設計本身：consumer 與 replica 共用一支 `Fetch`，是「replica 也只是 log 的讀者」這個抽象的紅利——讀取路徑、fetch session 機制都只需維護一份。帳單則是 replica 專屬語意不斷滲進共用 schema：`LogStartOffset` 欄位註明「只在 follower 發出時使用」（`FetchRequest.json:103`）；[KIP-903](https://cwiki.apache.org/confluence/display/KAFKA/KIP-903%3A+Replicas+with+stale+broker+epoch+should+not+be+allowed+to+join+the+ISR) 又為了 broker epoch 驗證在 v15 加入 `ReplicaState`、順勢廢棄扁平的 `ReplicaId`。抽象紅利先收，語意租金慢慢付。

> **小測驗 1**：replica fetch（broker↔broker）的 `Fetch` 版本怎麼決定？（答案見文末 [附錄 B](#appendix-b)）

---

## Part 2 — 失敗會有什麼訊息

本場只講「通訊當下協商不出版本」的錯誤。finalize / 升降當下的錯誤——`kafka-features upgrade` 的 `INVALID_UPDATE_VERSION`、fenced broker 擋住 feature/MV 升級、feature↔MV 依賴與降級規則——屬同系列《運行時的版本升降》那場，本場不展開，只在此點一句。

### 6. 沒有版本交集：client 端本地中止

client 不能直接用自己支援的最新版 API version，因為 broker 不一定支援。規則是取交集的最高版：

```text
chosen version = max(intersection(client allowed range, broker supported range))
```

當交集為空，`NodeApiVersions.latestUsableVersion(...)` 丟出 `UnsupportedVersionException`；`NetworkClient` 接到後**跳過 socket、不送出**，把 request 丟進 `abortedSends`，最後 producer / consumer / admin 各自在收到 `response.versionMismatch()` 時把錯誤交回應用層。關鍵：這類 `UnsupportedVersionException` 很多時候**不是 broker 回來的 response**，而是 client 在送出前就發現沒有可用 protocol version、本地 abort。

```text
client allows Produce 0-13 , broker supports 0-10  -> chosen 10
client allows Produce 11-13, broker supports 0-10  -> UnsupportedVersionException（送出前中止）
```

### 7. 繞過協商、直接送出不支援的版本：broker 關閉連線

若繞過協商、直接送出一個 broker 不支援的 API version，失敗路徑是固定的一條：broker 端 `RequestContext` 解析 request 失敗 → 丟出 `UnsupportedVersionException` → `SocketServer` 直接關閉連線（`RequestContext.java:112`、`SocketServer.scala:781`）。

為什麼關閉連線、而不是回一個錯誤 response？broker 其實讀得到 header（知道 apiKey 與版本），但一般 response 的序列化必須依 client 指定的 request version 進行——既然那個版本本身不受支援，broker 無法保證組出來的回應能被對方正確解讀。關閉連線是唯一可靠的動作。

唯一的例外是 `ApiVersions` 本身：它是 bootstrap 的逃生口——即使 client 送的 `ApiVersions` 版本超出 broker 支援範圍，broker 也不會關線，而是回一個 v0 的 response 帶 `UNSUPPORTED_VERSION` 錯誤碼與自己支援的版本範圍，讓 client 得以 recover、重新協商。

> **小測驗 2**：client 繞過協商、直接送出 broker 不支援的版本會怎樣？（答案見文末 [附錄 B](#appendix-b)）

### 8. 版本截斷：為什麼「升一點點」不夠

第 6 節「交集為空」最容易被忽略的根因是——**舊的 wire protocol API version 會被整個移除**。每個 API 都有自己的 `validVersions` 範圍，而這個範圍不保證永遠從 `0` 開始。

Kafka 4.0 就移除了一批舊 wire API 版本，例如 `FetchRequest.json` 的 `validVersions` 已是 `"4-18"`（Fetch v0–v3 移除，min 升到 4）。因此若 client 太舊、只會講已被截斷的版本，就會落到交集外——協商結果直接是 no usable version。這時「再升一點點」沒用，得跨過 upgrade guide 的版本下限（升任一端到 4.0 前，另一端要 ≥ 2.1，雙向要求）。

值得停一秒看這個決策的形狀。從 0.8.0（2013）到 3.x，Kafka 保留了每一個 protocol API 版本整整九年——「相容性至上」推到極端的取捨：好處是任何老 client 永遠連得上；代價是每個舊版本都是活的程式碼路徑與測試矩陣，broker 永遠不能假設 client 具備任何新能力。4.0 用 KIP-896 把 baseline 收到 2.1（2018），本質是一次帳務結算：用「斷掉 2018 年以前的 client」換「刪碼、縮測試面、讓協定假設前進七年」。為什麼等這麼久？因為斷 client 是不可逆的破壞性變更，只有 major 版本邊界的社會契約付得起。我的評價：收得對，甚至偏晚——九年的窗說明這個專案在相容性上保守到近乎自虐，而這份保守正是理解 Kafka 一切版本設計的鑰匙。

### Recap

本場的因果鏈只有一條：client 長壽、broker 滾動升級 → 版本天生對不齊 → 只能在連線當下決定每條連線講第幾版——client↔broker 與 broker↔controller 靠 `ApiVersionsRequest` 查詢後取交集協商，partition replication 的 `Fetch` 由 finalized MV 集中決定。「一個版本打天下」不成立的最好證據，就是那顆 4.1 broker：**同一支 `Fetch`，同一時刻，對 Kafka 2.4 老 client 講 v11、對 follower 講 v17**。而當版本選不出來：交集為空時 client 在送出前本地中止；繞過協商直接送出不支援的版本，broker 解析失敗、關閉連線。至於 finalize / 升降當下會出什麼錯，交給同系列《運行時的版本升降》。

最後一句立場：**per-API 細粒度協商＋replication 集中治理，這套組合是為「client 生態極度分散、API 數十支」的系統量身打造的取捨**——對 Kafka 划算，但不是通用解。MongoDB 選了較粗的全域粒度、PostgreSQL 乾脆放棄複製層的版本治理，各自都對得上自家的生態；讀懂一個系統的版本設計，就是讀懂它對自己使用者的假設。

---

## 附錄 A：原始碼對照 {#appendix-a}

> 正文把機制講完，這裡放對應的 source 片段與 file:line。commit 基準 trunk `b7b1c0a8`。

### A1 — Builder 版本範圍與協商

- `AbstractRequest.Builder` 帶 `[oldestAllowedVersion, latestAllowedVersion]`，`NetworkClient` 送出前取交集。
- 四條路徑的發起端共用同一顆 `org.apache.kafka.clients.NetworkClient`、各帶一份 `ApiVersions` cache——是否先送 `ApiVersionsRequest` 由建構參數 `discoverBrokerVersions` 決定：client、controller、KRaft 路徑為 true，replica fetcher 為 false（實作細節，正文不展開）。
- client↔broker 取交集最高版：`NodeApiVersions.latestUsableVersion(...)`（`clients/src/main/java/org/apache/kafka/clients/NodeApiVersions.java:149`）。
- Doc：`server-common/src/main/java/org/apache/kafka/server/common/MetadataVersion.java:31`「when communicating with clients, the client decides on the API version.」

### A2 — broker ↔ controller（broker 當 client）

- `BrokerLifecycleManager.java:580`：`channelManager.sendRequest(new BrokerHeartbeatRequest.Builder(data), handler)`。
- `BrokerHeartbeatRequest.Builder` 只有 `super(ApiKeys.BROKER_HEARTBEAT)`、未 pin 版本 → 全範圍協商。
- 底層：`NodeToControllerChannelManagerImpl.java:67`（`private final ApiVersions apiVersions`）、`:115` / `:129`（`NetworkClient` 吃 `apiVersions`）。
- 走這條管線的 request：`BrokerHeartbeat`、`BrokerRegistration`、`ControllerRegistration`、`AssignReplicasToDirs`。

### A3 — broker ↔ broker（複製面）三層分工 {#a3}

> 正文只講「複製面的 `Fetch` 由 MV 決定，其餘都是協商」；完整版是三支 RPC 三種作法（`core/src/main/scala/kafka/server/RemoteLeaderEndPoint.scala`）。先補一個名詞：`ListOffsets` = 把時間戳／哨兵（earliest / latest / by-timestamp）換算成一個 offset（consumer 的 `seekToBeginning`、`offsetsForTimes` 靠它，follower 則用來找截斷點）。

| RPC | 版本怎麼訂 | 證據 |
| --- | --- | --- |
| `Fetch` | MV **exact pin**（`[v, v]`） | `RemoteLeaderEndPoint.scala:215`；`FetchRequest.java:170-172` |
| `ListOffsets` | MV 當**上限**再協商（`[oldest, MV]`） | `RemoteLeaderEndPoint.scala:122`；`ListOffsetsRequest.java:88-90` |
| `OffsetsForLeaderEpoch` | **寫死常數 v4**，非 MV、非協商 | `OffsetsForLeaderEpochRequest.java:60-65`（`Builder.forFollower(...)` → `new Builder((short)4, (short)4, data)`） |

- 底層 `BrokerBlockingSender.scala:82` 仍是 `NetworkClient`，但 `:95` 的 `discoverBrokerVersions=false`——不送 `ApiVersionsRequest`，直接照 MV 決定的版本送。
- `MetadataVersion` 裡跟 RPC 版本有關的方法只有兩個：`fetchRequestVersion()`（`:273`）與 `listOffsetRequestVersion()`（`:289`），只作用在複製面的 `Fetch` / `ListOffsets`。
- 一句解讀：同一條複製路徑上三支 RPC 三種選版法，並非設計失誤。`OffsetsForLeaderEpoch` 的 v4 固定值是移除舊 `MetadataVersion` 之後留下的簡化（KAFKA-18465 清理的結果），程式碼並明示：未來若要加新版本，須改用 `metadata.version` gate（`OffsetsForLeaderEpochRequest.java:64` 的註解）。

`MetadataVersion.fetchRequestVersion()`（`server-common/src/main/java/org/apache/kafka/server/common/MetadataVersion.java:273`）：

```java
public short fetchRequestVersion() {
    if (isAtLeast(IBP_4_1_IV1)) {
        return 18;
    } else if (isAtLeast(IBP_3_9_IV0)) {
        return 17;
    } else if (isAtLeast(IBP_3_7_IV4)) {
        return 16;
    } else if (isAtLeast(IBP_3_5_IV1)) {
        return 15;
    } else if (isAtLeast(IBP_3_5_IV0)) {
        return 14;
    } else {
        return 13;
    }
}
```

### A4 — KRaft metadata-log Fetch：協商 + 獨立 feature（進階） {#a4}

> 正文第 4 節「雞生蛋」的鐵證在此。broker 以 observer、controller quorum 彼此之間抓 metadata log 的 `Fetch`，走協商、不看 MV。

- `KafkaRaftClient.buildFetchRequest()`（`:2985`）→ `RaftUtil.singletonFetchRequest(...)` → `KafkaNetworkChannel.buildRequest`（`:192`）包成 `FetchRequest.SimpleBuilder`。
- `SimpleBuilder` = `super(ApiKeys.FETCH)` 全範圍（`FetchRequest.java:133`）→ `ApiVersions` 協商，不是 MV。
- 這條路徑的能力由**獨立於 MV 的 `kraft.version` feature** 治理（`KRaftVersion.java`）；`KafkaRaftClient.java:185` 的 `localSupportedKRaftVersion: SupportedVersionRange` 是各節點自報的支援範圍。

### A5 — feature 與 RPC 正交、honor（進階） {#a5}

> 正文只留了一句「feature 不決定 RPC 版本」；完整版如下。這段與《版本定義》場的 feature gate 相鄰，放附錄避免搶正文主線。

RPC 版本與 feature 是**兩條正交的軸，要用一個功能得兩個都滿足**：

- **RPC 支援（wire 能力）**：兩端「能不能講」這支 RPC / 這個版本——由 binary 能力 + `ApiVersions` 協商決定（複製面由 MV）。
- **feature（叢集政策）**：叢集「准不准用」這個功能——由 metadata log 裡 finalized 的 feature level 決定。

要點：

- **feature 不決定 RPC 版本**。只有 MV 對複製面的 `Fetch` / `ListOffsets` 這麼做；feature 產出的是布林能力或 record 格式版本。
- **feature 真正 gate 的是 broker 要不要 honor 一支 RPC。** honor 指 broker 收到請求後「認這筆請求、照該功能的語意去處理」，而不是拒絕、回錯或忽略。RPC 在 binary 裡一直存在、也能協商成功送達，但 broker 會查 finalized feature 決定是否 honor：`group.version`（KIP-848）未開時，`handleConsumerGroupHeartbeat` 直接 fail（`KafkaApis.scala:2642-2650`）；`share.version` 未開時不受理 `ShareFetch`，toggle off 時清掉 share session（`SharePartitionManager.java:633`、`KafkaApis.scala:4290`）。
- **對外 feature 需要 RPC 承載**：新功能通常帶新 RPC（如 `ConsumerGroupHeartbeat`、`ShareFetch` / `ShareAcknowledge`）或現有 RPC 的更高版本。純內部 feature 不需要對外 RPC（只改 record 格式或內部行為）。
- **帶版本的 feature 反向用 RPC 版本推能力**：`transactionVersionForAddPartitionsToTxn(request)` 看 request 版本 > 3 → client 支援 TV2（`TransactionVersion.java:67`）。方向與「feature → RPC 版本」相反。

### A6 — 失敗路徑

client 端取交集（`clients/src/main/java/org/apache/kafka/clients/NodeApiVersions.java:149`）：

```java
Optional<ApiVersion> intersectVersion = ApiVersionsResponse.intersect(supportedVersion,
    new ApiVersion()
        .setApiKey(apiKey.id)
        .setMinVersion(oldestAllowedVersion)
        .setMaxVersion(latestAllowedVersion));

if (intersectVersion.isPresent())
    return intersectVersion.get().maxVersion();
else
    throw new UnsupportedVersionException(...);
```

`NetworkClient.doSend(...)` 接到 `UnsupportedVersionException` 後跳過 socket、丟進 `abortedSends`（`NetworkClient.java:591` 呼叫、`:597` catch）；`NetworkClient.poll(...)` drains aborted sends（`:651` / `:940`）。Producer / Consumer 收到 `response.versionMismatch()`（`Sender.java:595`、`ConsumerNetworkClient.java:614`）。

broker 端收到不支援版本的路徑：`RequestContext` 解析 request 失敗、丟 `UnsupportedVersionException`（`clients/src/main/java/org/apache/kafka/common/requests/RequestContext.java:112`）→ `SocketServer` 關閉連線（`core/src/main/scala/kafka/network/SocketServer.scala:781`）。

broker 組 `ApiVersionsResponse` 時放進每個 API 的 min/max（`clients/src/main/java/org/apache/kafka/common/protocol/ApiKeys.java:287`）；`ApiVersions` 對外仍宣告 v0 的特例讓它成為 bootstrap 逃生口。版本截斷的 schema 證據：`FetchRequest.json:61` = `"4-18"`、`ListOffsetsRequest.json:45` = `"1-11"`、`ProduceRequest.json` = `"3-13"`。upgrade guide 對 4.0 截斷的說明：`docs/getting-started/upgrade.md:229`。

---

## 附錄 B：常見誤解 / 隨堂考 {#appendix-b}

> 兩題對應兩段主線，每題附「直覺答案（多半錯）」與正解，可當現場有獎徵答。

### Q1：replica fetch（broker↔broker）的 `Fetch` 版本怎麼決定？

- **直覺**：兩個 broker 用 `ApiVersions` 協商取交集。
- **正解**：不協商。由 finalized `metadata.version` 決定（`fetchRequestVersion(MV)`，建成 `[v, v]` exact pin）；所有 broker 從同一個 MV 推出同一版。
- **出處**：`RemoteLeaderEndPoint.scala:215`、`MetadataVersion.java:273`

### Q2：client 繞過協商、直接送出 broker 不支援的 API version，會怎樣？

- **直覺**：broker 一律回一個 `UNSUPPORTED_VERSION` 錯誤碼。
- **正解**：一般 API → `RequestContext` 解析失敗、broker 丟 `UnsupportedVersionException`，`SocketServer` 關閉連線。唯一例外是 `ApiVersions`：它是 bootstrap 逃生口，會回 v0 response 帶 `UNSUPPORTED_VERSION` 錯誤碼 + 支援範圍讓 client recover。
- **出處**：`RequestContext.java:112`、`SocketServer.scala:781`、`ApiKeys.java`（`ApiVersions` 特例）

## 附錄 C：別家怎麼答同一題 {#appendix-c}

> 完整對照（含來源 URL）的來源 URL 見各家官方文件；這裡只留骨架。

| | client↔server 版本 | 節點間／複製層版本治理 |
| --- | --- | --- |
| **Kafka** | per-API `[min,max]` 協商（粒度最細、協定面積最大） | finalized `metadata.version`（手動 finalize、跟 binary 脫鉤） |
| **MongoDB** | 全域 wire version（`hello` 交換） | `featureCompatibilityVersion`——與 MV 同一套思路 |
| **PostgreSQL** | 全域協定版本（v3 逾二十年未變） | **無**——實體複製鎖 major（WAL 跨大版不相容），HA replica 不能就地跨 major 滾；跨大版近零停機得改搭 logical replication（bolt-on） |

兩個讀法：MongoDB 的 FCV 證明「叢集能力世代跟 binary 脫鉤、手動 finalize」不是 Kafka 的怪癖，而是分散式系統滾動升級的共同答案；PostgreSQL 則是反事實——複製層本身不做版本治理，實體複製鎖 major，跨大版的近零停機升級只能靠外掛的 logical replication，而非複製層內建。
