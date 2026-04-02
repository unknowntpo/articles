# Kafka 4.2.0 KIP-1034：內建 DLQ，終結手動錯誤處理

## Outline

- [ ] **前言**
  - Kafka 4.2.0 透過 KIP-1034 內建 DLQ，取代過去繁瑣的手動處理
  - 情境設定：click event 處理服務，value 為 JSON，可能有髒資料

- [ ] **問題背景：為什麼需要 DLQ？**
  - Topology：`click-events` → deserialize → process → `click-events-output`
  - 髒資料會丟 exception，若不處理整個 stream 會停止
  - DLQ 模式：把壞資料送去獨立 topic，由別的程式補救

- [ ] **Before（Kafka 3.9.x）：手動 DLQ 的兩條路徑**

  - [ ] **Path A：Processing Error（在 topology 內）**
    - 可以用 `flatMap` try-catch 或 `split`/`branch` 攔截
    - 若透過 `context.forward()` → 走 `RecordCollector` → 同一個 TX → tx-safe
    - 若用獨立 `KafkaProducer`（常見做法）→ 在 TX 之外 → NOT tx-safe
    - 程式碼：`ClickEventManualDlqTopology.java`

  - [ ] **Path B：Deserialization Error（在 topology 之前）**
    - Deserialization 發生在 `consumer.poll()` 之後，任何 processor 執行之前
    - `flatMap`、`split`、`branch` 全部幫不上忙
    - 只能靠 `DeserializationExceptionHandler`
    - 3.9.x 的 handler interface：只能回傳 `CONTINUE` 或 `FAIL`，無法回傳 DLQ records
    - 若在 handler 裡用獨立 producer 送 DLQ → NOT tx-safe
    - 程式碼：`ManualDlqHandler.java`

  - [ ] **共同痛點：獨立 Producer + EOS = 重複寫入**
    - Streams TX 範圍：`BEGIN TX` → deserialize + process → `RecordCollector.send()` → `sendOffsetsToTransaction()` → `COMMIT TX`
    - 獨立 `KafkaProducer.send()` 在 TX 之外，已 commit 無法 rollback
    - TX abort → Streams retry → DLQ 重複寫入
    - 圖解：正常 vs ABORT 的對比時序

- [ ] **After（Kafka 4.2.0 / KIP-1034）：一行搞定**

  - [ ] **Interface 的改變**
    - 4.2.0 新增 `handleError()` → 可回傳 `Response.resume(dlqRecords)`
    - 3.9.2 vs 4.2.0 interface signature 對比（`javap` 反編譯）

  - [ ] **框架自動處理的事**
    - `LogAndContinueExceptionHandler` override `handleError()` 自動建 DLQ record
    - `ExceptionHandlerUtils` 自動加 6 個 `__streams.errors.*` headers
    - `RecordCollectorImpl` 送 DLQ record → 同一個 `StreamsProducer` → 同一個 TX → tx-safe

  - [ ] **程式碼展示**
    - `ClickEventTopology.java`（零 DLQ 相關程式碼）
    - `App.java` 的一行 config：`ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG`

- [ ] **Before vs After 對比表**
  - 欄位：Processing error / Deserialization error / Tx-safe / Error headers 自動加 / 程式碼量

- [ ] **用測試驗證行為（不需要 broker）**
  - `before`：`ClickEventManualDlqTopologyTest`
    - `TopologyTestDriver` + `@Mock KafkaProducer` + `ArgumentCaptor`
    - 3 cases：valid / invalid / mixed
  - `after`：`ClickEventTopologyTest`
    - `TopologyTestDriver` + `ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG`
    - 4 cases：valid / invalid / DLQ header 驗證 / mixed
  - 執行：`./gradlew test`

- [ ] **結語**
  - 適用版本：需 Kafka 4.2.0+
  - 下一步：搭配 `exactly_once_v2` 驗證 tx-safety
  - 參考：KIP-1034
