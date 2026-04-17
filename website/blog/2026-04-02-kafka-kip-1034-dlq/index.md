---
title: "Kafka 4.2.0 KIP-1034：內建 DLQ，終結手動錯誤處理"
slug: kafka-kip-1034-dlq
authors: [unknowntpo]
tags: [Kafka, Kafka Streams, DLQ]
---

本篇搭配的範例程式已一併放進這個 repo：[`examples/kafka/kip-1034-dlq-blog-post/`](https://github.com/unknowntpo/articles/tree/master/website/examples/kafka/kip-1034-dlq-blog-post)。

`before/` 對應 Kafka 4.2.0 以前常見的手動 DLQ 作法，`after/` 對應 Kafka 4.2.0 / KIP-1034 的內建 DLQ 作法。

Kafka Streams 應用在處理資料流時，經常需要面對不合法或無法反序列化的 record。遇到這類資料時，系統可以選擇 fail，讓應用停止；也可以選擇 continue，略過該筆資料。實務上通常還有第三種需求：保留錯誤資料，寫入另一個 topic，供後續修補、重送、追查或告警使用。這正是 DLQ（Dead Letter Queue）的用途。

問題在於，Kafka Streams 4.2.0 以前並未提供完整的內建 DLQ 寫入路徑。應用程式可以自行補上這項能力，但必須同時處理 producer lifecycle、error metadata、錯誤發生位置，以及 transaction 邊界；若啟用 `exactly_once_v2`，這些限制會更加明顯。

KIP-1034 補上的正是這段缺口。Kafka Streams 的內建 exception handlers 現在可以把 DLQ record 回交給框架，由框架透過既有寫入流程送出，使 DLQ 寫入得以回到 Kafka Streams 的 transactional write flow。以下以 repo 內的範例對照舊作法與新作法。

## 先界定問題

範例使用一個 `click-events` topic，內容為 JSON 字串：

```json
{"ad_id":"banner-A","count":3}
```

Streams topology 會把它讀進來、deserialize 成 `ClickEvent`，接著做一點簡單轉換，最後寫到 `click-events-output`。

整體流程可以想成：

```text
click-events -> deserialize -> process -> click-events-output
```

若資料格式正確，處理流程相當單純。但只要出現 `NOT_VALID_JSON` 這類資料，Kafka Streams 在進入 processor 前就可能失敗，因為 record 必須先由 bytes 轉為應用程式期待的物件；一旦反序列化失敗，後續 processor 尚未開始執行。

因此，在 Kafka Streams 中討論 DLQ，不能只理解為「catch exception 後寫入另一個 topic」。錯誤可能發生在 topology 之內，也可能發生在 topology 之前。

```text
case A: processing error

click-events -> deserialize -> process X -> output
                                |
                                +-> still inside topology


case B: deserialization error

click-events -> deserialize X -> process -> output
                  |
                  +-> processor 還沒開始
```

## Kafka 4.2.0 以前：兩種 exception，兩套處理方式

先看 Kafka 4.2.0 以前常見的手動 DLQ 作法。主要負擔在於，應用程式不是只需處理單一錯誤型態，而是必須分別處理兩種不同型態的 exception。repo 裡的 `before/` 將這兩種情況整理成可重現的範例，方便對照各自限制。

### Processing error：發生在 topology 裡

若錯誤出現在 topology 內部，應用程式仍有機會在 DSL 轉換或 processor 中處理。這裡示範的做法，是先讓資料正常完成 deserialization，接著在 `flatMap` 這類 DSL 轉換中執行 business rule，並於必要時自行 `try/catch`。

:::info 為什麼這裡用 `flatMap`？
這個 before 範例要表達的是手動 DLQ 的處理方式：資料先正常完成 deserialization，成功資料繼續往下送；若後續 business rule 失敗，資料改送 DLQ，主流程不再產生 output。對這種「成功 1 筆、失敗 0 筆」的 flow 而言，`flatMap` 比 `mapValues` 更自然。

也可對照後續 `after` 範例。KIP-1034 之後，DLQ 交回 Kafka Streams 內部處理，主流程只剩正常資料轉換，因此 `ClickEventTopology.java` 可直接使用 `mapValues`。若在這個 before 範例中直接拋出 exception，當然也可執行；但那就不是此處要示範的手動攔截與分流路徑。
:::

這段是 `ClickEventManualDlqTopology.java` 的核心：

```java
stream
    .flatMap((key, event) -> {
        try {
            if (event.count < 0) {
                throw new IllegalArgumentException("count must be non-negative");
            }
            String processed = "user=" + key + " clicked ad=" + event.adId + " count=" + event.count;
            return Collections.singletonList(KeyValue.pair(key, processed));
        } catch (Exception e) {
            sendToDlq(key, event, e);
            return Collections.emptyList();
        }
    })
    .to(outputTopic);
```

這段 code 的重點不在 `flatMap`，而在 `catch` 裡的 `sendToDlq()`。這裡的錯誤已經不是 JSON parse 失敗，而是 record 成功進入 topology 後，因 business rule 不合法而被手動導向 DLQ。舊版最常見的做法，是準備一個獨立的 `KafkaProducer`，遇到這類錯誤就直接寫入 DLQ。

這個方式能夠運作，也可以一併補上 error metadata：

```java
ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(dlqTopic, key, value);
dlqRecord.headers().add("error.message", cause.getMessage() != null
        ? cause.getMessage().getBytes() : "null".getBytes());
dlqRecord.headers().add("error.class", cause.getClass().getName().getBytes());
dlqProducer.send(dlqRecord).get();
```

限制在於，這個 `dlqProducer` 與 Kafka Streams 內部 producer 並非同一個 instance。應用程式不只要另行維護 producer，也無法把這條 DLQ 寫入納入 Kafka Streams 的同一個 transaction，因此 DLQ 寫入與主流程無法共同達成 EOS。

### Deserialization error：發生在 topology 之前

deserialization error 的限制更明確。

這類錯誤不是發生在 `map`、`flatMap`、`transform` 等 topology 步驟內，而是在 record 被 consumer 取回後、真正進入 topology 前就已發生。換言之，processor 尚未接手該筆資料，deserialization 已經失敗。

在這種情況下，topology 內的分流手段都無法介入。`flatMap`、`split()`、`branch()` 均無法觸及該筆資料；可用的處理入口只剩 `DeserializationExceptionHandler`。

:::info Kafka Streams 原始碼中的對應位置
`RecordQueue.addRawRecords()` 先把 raw records 放進 queue，接著 `updateHead()` 會呼叫 `recordDeserializer.deserialize(processorContext, raw)`；之後 `StreamTask.process()` 才從 `partitionGroup.nextRecord(...)` 取出 record，交給 `doProcess()` 傳進 source node。也就是說，deserialization 確實發生在 record 進入 topology 之前。

參考：
- [RecordQueue.addRawRecords()](https://github.com/apache/kafka/blob/4.2.0/streams/src/main/java/org/apache/kafka/streams/processor/internals/RecordQueue.java#L124-L129)
- [RecordQueue.updateHead()](https://github.com/apache/kafka/blob/4.2.0/streams/src/main/java/org/apache/kafka/streams/processor/internals/RecordQueue.java#L210-L217)
- [StreamTask.process()](https://github.com/apache/kafka/blob/4.2.0/streams/src/main/java/org/apache/kafka/streams/processor/internals/StreamTask.java#L789-L818)
- [StreamTask.doProcess()](https://github.com/apache/kafka/blob/4.2.0/streams/src/main/java/org/apache/kafka/streams/processor/internals/StreamTask.java#L884-L907)
:::

`ManualDlqHandler.java` 示範的就是這條路：

```java
@Override
public DeserializationHandlerResponse handle(
        ErrorHandlerContext context,
        ConsumerRecord<byte[], byte[]> record,
        Exception exception) {

    sendToDlq(record, exception);
    return DeserializationHandlerResponse.CONTINUE;
}
```

此處的限制相當直接：deserialization error 發生時，record 尚未進入 topology，因此不能用 topology 內的 routing 手段處理。`context.forward()` 無法使用，`branch()`、`split()`、`flatMap` 等 DSL 轉換也無法觸及該筆資料。若要寫入 DLQ，舊版常見作法仍是自行建立獨立的 `KafkaProducer`。

除了自行送出之外，應用程式也必須自行複製原始 headers，並補上 topic / partition / offset / exception 等 metadata：

```java
record.headers().forEach(h -> dlqRecord.headers().add(h));
dlqRecord.headers().add("__manual.error.topic", record.topic().getBytes());
dlqRecord.headers().add("__manual.error.partition",
        String.valueOf(record.partition()).getBytes());
dlqRecord.headers().add("__manual.error.offset",
        String.valueOf(record.offset()).getBytes());
```

這正是舊版作法的主要負擔：不同錯誤類型必須掛在不同處理位置，處理方式也不一致。

綜合 `before/` 的兩條路徑，application 層必須自行承擔下列責任：

- 判斷錯誤應於哪一層攔截；processing error 與 deserialization error 並非同一套寫法。
- 另行維護 `KafkaProducer`，包含 lifecycle、配置與送出失敗時的處理策略。
- 自行定義 headers 命名與需要攜帶的 metadata。
- 在 EOS 開啟時承擔 DLQ 可能於 retry 過程中重複寫入的風險。
- 測試往往必須圍繞 workaround 撰寫，而不是直接驗證框架行為。

舊版的問題不只是程式碼較多，而是錯誤處理、資料一致性與 observability 的責任都被推回 application 層。

## 真正的痛點在 tx-safe

即使接受「只能自行寫入 DLQ」這個前提，transaction 邊界仍然沒有解決。

Kafka Streams 在 EOS 模式下會自行管理 transaction。簡化後的處理流程如下：

```text
BEGIN TX
  -> consume record
  -> deserialize
  -> process
  -> send output via RecordCollector
  -> sendOffsetsToTransaction
COMMIT TX
```

若在此流程中另以獨立 `KafkaProducer` 寫入 DLQ，問題相當直接：`dlqProducer` 與 Kafka Streams 內部用於送出 output 的 producer 不是同一個 producer instance。既然不是同一個 producer，就無法共享同一個 Kafka transaction。

用圖看會更清楚：

```text
Kafka Streams internal producer
  -> output records
  -> transaction A

manual dlqProducer
  -> DLQ records
  -> not part of transaction A
```

也就是說，手動送出的 DLQ record 不會落在 Kafka Streams 那條 transactional write path 裡。

可能發生的情境如下：

1. 手動 DLQ producer 已經將錯誤資料送出。
2. Kafka Streams 內部 transaction 隨後因 rebalance、crash 或其他原因 abort。
3. Kafka Streams retry 後重新處理同一筆資料。
4. DLQ record 再次被寫入。

:::caution
獨立 producer 送出的 record 不在 Kafka Streams 的 transaction 之內；abort 或 retry 不會使該筆 DLQ 寫入隨之 rollback，因此 DLQ 可能被重複寫入。這是舊版作法的根本限制：框架沒有提供正式且可納入 transaction 的 DLQ 寫入路徑。
:::

## Kafka 4.2.0 / KIP-1034：框架終於把這條路補起來

到了 Kafka 4.2.0，KIP-1034 將這件事正式納入 Kafka Streams 的 error handling flow。

核心方向是：exception handler 可以把要寫入 DLQ 的 records 回交給框架，由 Kafka Streams 透過內部 producer 送出。這項能力加在 Kafka Streams 內建的 deserialization / processing / production exception handling 流程上；本文的 `after/` 範例則聚焦於最常見、也最容易受舊版限制影響的 deserialization error。

這個改變的關鍵在於，只要 DLQ record 由框架送出，就能沿用 Kafka Streams 既有的 producer 與 transaction，而不必在 application 層另行建立獨立 producer。

KIP-1034 之後，topology 本身可以維持單純：

```java
builder
    .stream(inputTopic, Consumed.with(Serdes.String(), new ClickEventSerde()))
    .mapValues(event -> "user clicked ad=" + event.adId + " count=" + event.count)
    .to(outputTopic);
```

這段 `ClickEventTopology.java` 不包含任何 DLQ 相關 code；沒有手動 `try/catch`、沒有另行建立 producer，也沒有自行補 headers。DLQ 行為不再混入 topology。

真正啟用 DLQ 的設定位於 `App.java`。DLQ topic 由下列 config 指定：

```java
props.put(StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG, DLQ_TOPIC);
```

再搭配內建 deserialization / processing handlers：

```java
props.put(StreamsConfig.DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
        LogAndContinueExceptionHandler.class);
props.put(StreamsConfig.PROCESSING_EXCEPTION_HANDLER_CLASS_CONFIG,
        LogAndContinueProcessingExceptionHandler.class);
```

這裡也是 KIP-1034 最核心的 API 變化。舊版 exception handler 的回傳值本質上只是在回答「繼續」或「失敗」；4.2.0 之後，handler 的新 `Response` 可以額外攜帶「需要由框架送出的 DLQ records」。也因為 handler 現在可以把 DLQ records 回交給 Kafka Streams，框架才得以透過內部 `StreamsProducer` / `RecordCollector` 送出，而不是把 DLQ 寫入責任留在 application 層。

本文範例實際觸發的是 deserialization error，因此 `LogAndContinueExceptionHandler` 這條路徑的效果如下：

1. `ClickEventSerde` 反序列化失敗時，會拋 exception。
2. `LogAndContinueExceptionHandler` 會接手。
3. 4.2.0 的 handler 可以建立 DLQ record，並交還給 Kafka Streams。
4. Kafka Streams 透過 `RecordCollectorImpl` 用同一個 `StreamsProducer` 把 record 送出去。
5. 因為走的是同一個 producer，DLQ 寫入也落在同一個 transaction 邊界內。

`LogAndContinueProcessingExceptionHandler` 則處理 topology 內部的 processing error。Kafka Streams 4.2.0 的 `processing.exception.handler` 預設值是 `LogAndFailProcessingExceptionHandler`；即使設定了 `errors.dead.letter.queue.topic.name`，預設 handler 仍會回傳 fail。因此，若 processing error 也要「寫入 DLQ 後繼續」，必須明確設定 `LogAndContinueProcessingExceptionHandler`。

補充一點：`ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG` 之所以有效，是因為內建 exception handlers 會讀取該 config，並透過 Kafka Streams 內部工具建立 DLQ record；它不是框架對所有 handler 強制套用的行為。多數情境下，內建 handler 已足以涵蓋需求；若需要自訂 DLQ record 內容，仍可改用 custom handler。

但 custom handler 不必回到舊版的手動 producer 寫法。KIP-1034 之後，exception handler 介面本身已改變：舊版 `handle()` 只能回傳 CONTINUE 或 FAIL；新版 `handleError()` 回傳 `Response`，其中可以攜帶 `ProducerRecord` 列表，由 Kafka Streams 透過同一個內部 producer 送出。

```java
// Kafka 4.2.0 以前：若要寫入 DLQ，通常只能自行建立 producer
@Override
public DeserializationHandlerResponse handle(
        ErrorHandlerContext context,
        ConsumerRecord<byte[], byte[]> record,
        Exception exception) {
    ProducerRecord<byte[], byte[]> dlqRecord =
            new ProducerRecord<>("app-dlq", record.key(), record.value());
    dlqProducer.send(dlqRecord).get();  // 獨立 producer，不在 Streams tx 裡
    return DeserializationHandlerResponse.CONTINUE;
}

// Kafka 4.2.0：把 DLQ record 回交給框架，走同一條 transaction 路徑
@Override
public DeserializationExceptionHandler.Response handleError(
        ErrorHandlerContext context,
        ConsumerRecord<byte[], byte[]> record,
        Exception exception) {
    ProducerRecord<byte[], byte[]> dlqRecord =
            new ProducerRecord<>("app-dlq", record.key(), record.value());
    return DeserializationExceptionHandler.Response.resume(List.of(dlqRecord));
}
```

介面差異就在於：`handleError()` 允許 handler 把 DLQ records 回交給框架送出，不需要在 application 層另行建立 producer。

這是 KIP-1034 最重要的差別。它不只是省去自行建立 producer 的負擔，也讓 DLQ 寫入重新納入 Kafka Streams 的一致性模型。

換言之，本文所說的「透過 config 啟用 DLQ」成立的前提，是使用內建 handler；若改用 custom deserialization / processing / production handler，`errors.dead.letter.queue.topic.name` 不會自動替該 handler 建立 DLQ record，handler 必須自行決定是否建立 records。不過，custom handler 仍可透過 `Response.resume(...)` 把 records 回交給 Kafka Streams，因此依然可以走內建寫入路徑，而不需要自行建立 producer。

若拆開 `after` 帶來的差異，可以整理為下列幾點：

- 不必另行建立 DLQ producer；Kafka Streams 內部會負責送出。
- 不必把 DLQ 邏輯放入 topology；主流程可以維持單純。
- 不必自行補齊常見 error headers；exception / topic / partition / offset 等 metadata 由框架建立。
- deserialization error 不再需要 application 層 workaround；框架已提供正式處理路徑。
- KIP-1034 不只涵蓋 deserialization handler，也延伸到 processing / production exception handler；本文範例則聚焦在 deserialization path。`ProductionExceptionHandler` 處理的是 Kafka Streams 送出到下游時的寫入錯誤；4.2.0 以前，`handle()` 只能回傳 CONTINUE 或 FAIL，沒有 DLQ records；4.2.0 之後，`handleError()` 回傳 `Response`，可以攜帶 DLQ records，也具備 RETRY 選項。
- DLQ 與 `exactly_once_v2` 可以放在同一個 transaction 模型中理解。

整體而言，設定更集中，topology 更單純，error metadata 由框架補齊，測試也能直接驗證框架行為。更重要的是，DLQ 寫入回到 transaction 邊界內，能與 EOS 模型一致。

:::note
KIP-1034 只定義了「如何送出 DLQ record」以及「預設應攜帶哪些 headers」，但 DLQ topic 本身不會由 Kafka Streams 自動建立。

如果 broker 開啟 `auto.create.topics.enable=true`，topic 可以由 broker 的 auto-create 機制建立。但 production 環境通常不應依賴此行為：許多 cluster 會直接關閉 auto-create；即使開啟，topic 也會套用 broker 預設的 partitions、replication factor、retention、cleanup policy，未必符合 input / output / DLQ topic 的需求。

因此，repo 裡的 `after/src/main/java/io/example/App.java` 會先建立 `click-events-dlq` topic，而不是依賴 broker auto-create。
:::

`after` 的資料流則比較像這樣：

```text
                +-------------------+
                |   click-events    |
                +-------------------+
                          |
                          v
                +-------------------+
                |   deserialize     |
                +-------------------+
                    |           |
             success|           |error
                    v           v
             +-------------+   +------------------------------+
             |   process   |   | LogAndContinueExceptionHandler|
             +-------------+   +------------------------------+
                    |                         |
                    v                         v
          +-------------------+     +-------------------+
          | click-events-out  |     | click-events-dlq  |
          +-------------------+     +-------------------+

          both writes go through Kafka Streams internals
```

如果只看 transaction 邊界，`after` 的差異會更明顯：

```text
+---------------- Kafka Streams transaction ----------------+
| consume -> deserialize -> process                        |
|                    |                                     |
|                    +-> DLQ record via Kafka Streams     |
|                    +-> output record via Kafka Streams  |
|                                                           |
| both use the same StreamsProducer / RecordCollector       |
+--------------------- commit / abort ---------------------+
```

## 不只 tx-safe，連 error headers 也一起內建

另一項實用的改變，是 error metadata 不必再由 application 層自行補齊。

在舊版手動作法中，header 名稱、內容格式，以及是否攜帶原始 topic / partition / offset，都必須由應用程式自行決定。不同團隊可能各自定義一套格式，時間一久也容易分歧。

KIP-1034 之後，框架會自動附上這些 `__streams.errors.*` headers：

- `__streams.errors.exception`
- `__streams.errors.message`
- `__streams.errors.stacktrace`
- `__streams.errors.topic`
- `__streams.errors.partition`
- `__streams.errors.offset`

這個細節之所以重要，是因為 DLQ 並不只是承接錯誤資料。實務上，後續往往還牽涉到原因追查、告警、資料回補與 replay。若這些 metadata 能由框架穩定補齊，後續處理會更一致，也更容易被測試覆蓋。

## 總結

| 項目 | Kafka 4.2.0 以前的手動作法 | Kafka 4.2.0 / KIP-1034 |
| --- | --- | --- |
| Processing error | 可以自行攔截，但通常必須自行導向 DLQ | 可改走 4.2.0 新增的 processing exception handler 路徑 |
| Deserialization error | 只能掛 `DeserializationExceptionHandler`，且通常必須自行送出 | 可透過內建 DLQ flow 處理 |
| DLQ 寫入方式 | 常見作法是獨立 `KafkaProducer`，責任在 application 層 | 由 Kafka Streams 內部送出 |
| Tx-safe | 容易在 transaction 外送出，retry 時可能重複寫入 | 使用同一個 `StreamsProducer`，可納入同一個 transaction |
| Error headers | 必須自行補齊、命名與維護 | 框架附上 `__streams.errors.*` |
| 程式碼量 | topology、handler、headers、producer lifecycle 都要自行處理 | 搭配內建 handler 時，主流程通常只需配置即可啟用 |

Kafka 4.2.0 以前的 DLQ 較像 application 層自行補出的機制；KIP-1034 之後，DLQ 才正式進入 Kafka Streams 框架，並能與 transaction 模型一併運作。

## 用測試看行為差異，比講概念更準

許多細節放在測試中觀察會更直接，因為 input、output、DLQ record 與 headers 都能在同一處驗證。若要對照本文提到的 routing 與 header 行為，可直接閱讀 `before/` 與 `after/` 的測試。

若要自行執行範例，專案目錄如下：

```bash
cd examples/kafka/kip-1034-dlq-blog-post
./gradlew test
```

## 何時應評估升級到 4.2.0？

若系統具備下列需求，KIP-1034 的價值會相當明確：

- deserialization error 需要穩定寫入 DLQ。
- 不希望在 topology 外另行維護 producer。
- 已啟用 `exactly_once_v2`，且不希望承擔 transaction 外寫入導致的重複寫入風險。
- 需要一致的 error headers，以支援後續告警、查錯與回補。

換言之，4.2.0 之後，DLQ 不再只是 application 層自行補上的機制，而是 Kafka Streams 框架正式承接的責任。

## 結語

KIP-1034 補的是 Kafka Streams 長期存在的 DLQ 缺口。

Kafka 4.2.0 以前，真正制約 DLQ 設計的是 transaction 邊界：只要使用獨立 producer 寫入 DLQ，該筆寫入就脫離 Kafka Streams 的 transaction 範圍；在 EOS 開啟時，retry 可能造成重複寫入。除此之外，錯誤攔截層級、送出方式與 headers 命名也都必須由 application 層自行維護。

4.2.0 之後，設定 `StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG` 並搭配內建 exception handler，錯誤資料即可交由 Kafka Streams 內部寫入 DLQ，不必另行建立 producer。對於仍在維護手動 DLQ 的 Kafka Streams 專案，KIP-1034 值得納入升級評估。

參考資料：

- [KIP-1034: Dead letter queue in Kafka Streams](https://cwiki.apache.org/confluence/display/KAFKA/KIP-1034%3A+Dead+letter+queue+in+Kafka+Streams)
- [Apache Kafka 4.2.0 Release Announcement](https://kafka.apache.org/blog/2026/02/17/apache-kafka-4.2.0-release-announcement/)
- [Kafka Streams Configs: `errors.dead.letter.queue.topic.name`](https://kafka.apache.org/42/configuration/kafka-streams-configs)
