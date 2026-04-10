---
title: "Kafka 4.2.0 KIP-1034：內建 DLQ，終結手動錯誤處理"
slug: kafka-kip-1034-dlq
authors: [unknowntpo]
tags: [Kafka, Kafka Streams, DLQ]
---

本篇搭配的範例程式已一併放進這個 repo：[`examples/kafka/kip-1034-dlq-blog-post/`](https://github.com/unknowntpo/articles/tree/master/website/examples/kafka/kip-1034-dlq-blog-post)。

`before/` 對應 Kafka 4.2.0 以前常見的手動 DLQ 作法，`after/` 對應 Kafka 4.2.0 / KIP-1034 的內建 DLQ 作法。

如果你平常有在跑 Kafka Streams，應該多少都遇過這種情況：正常資料一路順順地進來，直到某一筆髒資料出現，整個 flow 就開始變得很尷尬。你當然可以選擇直接 fail，讓程式停掉；也可以選擇 continue，把壞資料跳過。但實務上通常還有第三個需求，就是把那筆壞資料留下來，丟到另一個 topic，之後再補救、重送，或至少做告警。這就是 DLQ（Dead Letter Queue）存在的理由。

問題是，在 Kafka Streams 4.2.0 以前，這件事沒有你想像中那麼直覺。你可以做，但往往要自己補很多東西，而且一不小心就會踩到 transaction 邊界，特別是你開了 `exactly_once_v2` 之後，事情會更麻煩。

Kafka 4.2.0 的 KIP-1034，就是把這一塊補起來。它帶來的改變不只是少寫幾行 code，而是把「送 DLQ」這件事正式拉回 Kafka Streams 自己的 transaction flow 裡面。這篇我想用 repo 裡的範例，從舊作法一路走到新作法，直接看差別到底在哪裡。

## 先看問題長什麼樣子

範例很簡單，我們有一個 `click-events` topic，裡面放的是 JSON 字串，內容像這樣：

```json
{"ad_id":"banner-A","count":3}
```

Streams topology 會把它讀進來、deserialize 成 `ClickEvent`，接著做一點簡單轉換，最後寫到 `click-events-output`。

整體流程可以想成：

```text
click-events -> deserialize -> process -> click-events-output
```

如果資料是乾淨的，事情很單純。但只要有一筆像 `NOT_VALID_JSON` 這種壞資料混進來，問題就來了。因為 Kafka Streams 在處理資料之前，得先把 bytes 轉成你要的物件。這一步如果失敗，後面的 processor 根本還沒開始跑。

這也是為什麼 DLQ 在 Kafka Streams 裡，不能只想成「catch exception 然後送另一個 topic」。你得先分清楚，錯誤是發生在 topology 裡，還是發生在 topology 之前。

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

先講 Kafka 4.2.0 以前常見的手動 DLQ 作法。麻煩的地方在於，你不是只要接一種錯誤就好，而是得分別處理兩種不同型態的 exception。repo 裡的 `before/` 只是把這兩種情況整理成可重現的範例，方便對照它們各自的限制。

### 路線一：processing error 發生在 topology 裡

如果錯誤是出現在 topology 內部，你還有很多地方可以攔。這裡示範的做法，是先讓資料正常完成 deserialization，接著在 `flatMap` 這類 DSL 轉換裡處理後續的 business rule，並在需要時自己 `try/catch`。

:::info 為什麼這裡用 `flatMap`？
這個 before 範例要表達的是手動 DLQ 的處理方式：資料先正常完成 deserialization，成功的資料繼續往下送；若後面的 business rule 失敗，資料就改送 DLQ，主流程不再產生 output。對這種「成功 1 筆、失敗 0 筆」的 flow 來說，`flatMap` 會比 `mapValues` 自然很多。

你也可以順便和後面的 `after` 範例對照看。到了 KIP-1034 之後，DLQ 交回 Kafka Streams 內部處理，主流程只剩正常資料轉換，所以 `ClickEventTopology.java` 裡就可以直接用 `mapValues`。若在這個 before 範例裡直接丟 exception，當然也可以，但那就不會是這裡想示範的手動攔截與分流路徑。
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

這段 code 要看的重點不在 `flatMap`，而是在 `catch` 裡那個 `sendToDlq()`。這裡的錯誤已經不是 JSON parse 失敗，而是 record 成功進入 topology 之後，因為 business rule 不合法而被手動導去 DLQ。舊版最常見的做法，就是自己準備一個獨立的 `KafkaProducer`，遇到這類錯誤就直接送去 DLQ。

看起來很合理，因為它真的能動，而且你也可以順手把 error metadata 補上去：

```java
ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(dlqTopic, key, value);
dlqRecord.headers().add("error.message", cause.getMessage() != null
        ? cause.getMessage().getBytes() : "null".getBytes());
dlqRecord.headers().add("error.class", cause.getClass().getName().getBytes());
dlqProducer.send(dlqRecord).get();
```

問題是，這個 `dlqProducer` 跟 Kafka Streams 內部那個 producer 是兩回事。這代表你得自己多維護一個 producer，而不是讓 Streams 用內部機制幫你送。更重要的是，這個做法無法納入 Kafka Streams 的同一個 transaction，因此也沒辦法讓 DLQ 這條寫入路徑和主流程一起達成 EOS。

### 路線二：deserialization error 發生在 topology 之前

更麻煩的是 deserialization error。

這種錯誤不是發生在 `map`、`flatMap`、`transform` 那些步驟裡，而是在 record 被 consumer 撈回來之後、真正進入 topology 之前就發生了。換句話說，processor 還沒真正接手這筆資料，deserialization 就已經失敗了。

這種情況下，你在 topology 裡做任何分流都沒用。`flatMap` 幫不上忙，`split()` 幫不上忙，`branch()` 也幫不上忙。可用的處理入口只剩 `DeserializationExceptionHandler`。

:::info Kafka Streams 原始碼中的對應位置
`RecordQueue.addRawRecords()` 先把 raw records 放進 queue，接著 `updateHead()` 會呼叫 `recordDeserializer.deserialize(processorContext, raw)`；之後 `StreamTask.process()` 才從 `partitionGroup.nextRecord(...)` 取出 record，交給 `doProcess()` 傳進 source node。也就是說，deserialization 確實發生在 record 進入 topology 之前。

參考：
- [RecordQueue.addRawRecords()](https://github.com/apache/kafka/blob/trunk/streams/src/main/java/org/apache/kafka/streams/processor/internals/RecordQueue.java#L976-L985)
- [RecordQueue.updateHead()](https://github.com/apache/kafka/blob/trunk/streams/src/main/java/org/apache/kafka/streams/processor/internals/RecordQueue.java#L1114-L1126)
- [StreamTask.process()](https://github.com/apache/kafka/blob/trunk/streams/src/main/java/org/apache/kafka/streams/processor/internals/StreamTask.java#L3497-L3533)
- [StreamTask.doProcess()](https://github.com/apache/kafka/blob/trunk/streams/src/main/java/org/apache/kafka/streams/processor/internals/StreamTask.java#L3654-L3694)
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

這邊的限制其實很直接：deserialization error 發生時，record 還沒進入 topology，所以你不能用 topology 內的 routing 手段來處理它。`context.forward()` 用不上，`branch()`、`split()`、`flatMap` 這些 DSL 轉換也完全碰不到這筆資料。結果就是，如果你想把它送進 DLQ，通常只能自己建一個獨立的 `KafkaProducer`。

而且不只要自己送，你還得自己複製原始 headers、自己補 topic / partition / offset / exception 這些 metadata：

```java
record.headers().forEach(h -> dlqRecord.headers().add(h));
dlqRecord.headers().add("__manual.error.topic", record.topic().getBytes());
dlqRecord.headers().add("__manual.error.partition",
        String.valueOf(record.partition()).getBytes());
dlqRecord.headers().add("__manual.error.offset",
        String.valueOf(record.offset()).getBytes());
```

這就是舊版最麻煩的地方：每種錯誤都得自己找對應的處理位置，處理方式還不一樣。

把 `before/` 這兩條路放在一起看，痛點其實很明確，而且每一個都很實務：

- 你得自己決定錯誤該在哪一層攔，processing error 跟 deserialization error 還不是同一套寫法。
- 你得自己養一把額外的 `KafkaProducer`，包含 lifecycle、設定、送出失敗時怎麼處理。
- 你得自己定義 headers，要帶哪些 metadata、命名規則怎麼訂，全都要自己維護。
- 你得自己承擔 tx-safe 的風險，尤其在 EOS 打開時，DLQ 重複寫入不是理論問題，而是真的可能在 retry 時出現。
- 你得自己測，連測試都會被迫圍繞 workaround 來寫，而不是直接驗證框架行為。

換句話說，舊版麻煩的不是程式碼多幾行，而是你為了補一個框架沒內建的能力，最後會把錯誤處理、資料一致性、甚至 observability 的責任一起扛到 application 層。

## 真正的痛點在 tx-safe

前面在談 deserialization error 那一段，其實只回答了一半的問題：為什麼這類錯誤不能靠 `flatMap`、`branch()`、`split()` 這些 topology 內的手段處理。接下來真正麻煩的，是就算你接受「只能自己送 DLQ」這件事，transaction 邊界的問題還是沒解決。

Kafka Streams 在 EOS 模式下，自己會管理 transaction。簡化來看，它的處理流程大概是這樣：

```text
BEGIN TX
  -> consume record
  -> deserialize
  -> process
  -> send output via RecordCollector
  -> sendOffsetsToTransaction
COMMIT TX
```

如果你在這個過程中，另外拿一個獨立的 `KafkaProducer` 去送 DLQ，問題其實很直接：這個 `dlqProducer` 和 Kafka Streams 內部用來送 output 的 producer，不是同一個 producer instance。既然不是同一個 producer，就不可能共享同一個 Kafka transaction。

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

進一步來看，可能會發生這種事：

1. 你的手動 DLQ producer 已經把壞資料送出去了。
2. 但 Streams 自己那個 transaction 後面因為 rebalance、crash，或其他原因 abort。
3. Streams retry 之後，又重新處理同一筆資料。
4. 你的 DLQ 又被送一次。

結果就是，這條 DLQ 寫入路徑看起來能用，但其實可能在 retry 時重複寫入。

這也是手動 DLQ 最麻煩的地方：只要你是另外用獨立 producer 把資料送出去，那筆寫入就不會落在 Kafka Streams 的 transaction 裡。後面如果發生 abort 或 retry，DLQ 這條路徑自然也不會跟著 rollback。

所以舊版真正卡住的點，是框架本身沒有給你一條正式、內建、可 tx-safe 的 DLQ 路。

## Kafka 4.2.0 / KIP-1034：框架終於把這條路補起來

到了 Kafka 4.2.0，KIP-1034 把這件事正式拉進 Kafka Streams 的 error handling flow。

簡單講，新的方向是：exception handler 不再只能回答「繼續」或「失敗」，它還可以把要送去 DLQ 的 records 回交給框架，讓框架自己送。

這裡的改變很關鍵，因為一旦是框架自己送，就能走它原本那套 producer、原本那個 transaction，而不是你另外繞出去開一個獨立 producer。

到了 KIP-1034 之後，topology 本身會乾淨很多：

```java
builder
    .stream(inputTopic, Consumed.with(Serdes.String(), new ClickEventSerde()))
    .mapValues(event -> "user clicked ad=" + event.adId + " count=" + event.count)
    .to(outputTopic);
```

這段 `ClickEventTopology.java` 沒有任何 DLQ 相關 code。沒有手動 `try/catch`、沒有自建 producer、沒有自己補 headers。所有 DLQ 的事情，都不在 topology 裡面處理。

真正啟用 DLQ 的地方，只在 `App.java` 這一行：

```java
props.put(StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG, DLQ_TOPIC);
```

再搭配 deserialization handler：

```java
props.put(StreamsConfig.DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
        LogAndContinueExceptionHandler.class);
```

這兩行合起來的效果是：

1. `ClickEventSerde` 反序列化失敗時，會拋 exception。
2. `LogAndContinueExceptionHandler` 會接手。
3. 4.2.0 的 handler 可以建立 DLQ record，並把它交還給 Kafka Streams。
4. Kafka Streams 透過 `RecordCollectorImpl` 用同一個 `StreamsProducer` 把 record 送出去。
5. 因為走的是同一個 producer，所以它也落在同一個 transaction 裡。

這就是 KIP-1034 最重要的差別。它不只是幫你省掉自己建 `ProducerRecord` 的麻煩，也把 DLQ 重新納入 Kafka Streams 的一致性模型裡。

如果把 `after` 帶來的方便功能拆開看，大概可以整理成這幾件事：

- 你不用再手動建 DLQ producer，Kafka Streams 內部會直接幫你送。
- 你不用再把 DLQ 邏輯塞進 topology，主流程可以維持乾淨。
- 你不用再自己補一堆 error headers，常用的 exception / topic / partition / offset 都會自動帶上。
- 你不用再為 deserialization error 另外發明一套 workaround，框架已經有正式路徑可以處理。
- 你比較容易把 DLQ 跟 `exactly_once_v2` 放在一起思考，因為現在它終於回到同一個 transaction 模型裡。

對日常維運來說，這些改變的價值很直接：設定比較少、拓樸比較乾淨、錯誤資訊比較完整、測試比較好寫，更重要的是，DLQ 不再是 transaction 外的額外寫入，整體行為也比較能和 Kafka Streams 的 EOS 模型對齊。

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

另一個很實用的改變，是 error metadata 不用自己補了。

在舊版手動作法裡，你要自己決定 header 叫什麼、內容放什麼、要不要帶原始 topic / partition / offset。每個團隊都可能長得不一樣，而且時間久了很容易散掉。

KIP-1034 之後，框架會自動附上這些 `__streams.errors.*` headers：

- `__streams.errors.exception`
- `__streams.errors.message`
- `__streams.errors.stacktrace`
- `__streams.errors.topic`
- `__streams.errors.partition`
- `__streams.errors.offset`

這些 headers 也能在 `ClickEventTopologyTest.java` 裡直接驗證到。測試會把 DLQ record 讀出來，確認這幾個 header 都真的存在。

```java
assertTrue(headerNames.contains("__streams.errors.exception"));
assertTrue(headerNames.contains("__streams.errors.message"));
assertTrue(headerNames.contains("__streams.errors.stacktrace"));
assertTrue(headerNames.contains("__streams.errors.topic"));
assertTrue(headerNames.contains("__streams.errors.partition"));
assertTrue(headerNames.contains("__streams.errors.offset"));
```

這個細節之所以重要，是因為 DLQ 並不只是承接錯誤資料而已。實務上，後續往往還牽涉到原因追查、告警、資料回補，甚至 replay。若這些 metadata 能由框架穩定補齊，後續處理就會省事許多。

## 總結

| 項目 | Kafka 4.2.0 以前的手動作法 | Kafka 4.2.0 / KIP-1034 |
| --- | --- | --- |
| Processing error | 可以自己攔，但通常得自己把錯誤導去 DLQ | 可延續框架內建處理方式 |
| Deserialization error | 只能掛 `DeserializationExceptionHandler`，而且只能自己送 | 可直接透過內建 DLQ flow 處理 |
| DLQ 寫入方式 | 常見是獨立 `KafkaProducer`，責任全在 application | 由 Kafka Streams 內部送出 |
| Tx-safe | 很容易踩到 transaction 外送出，retry 時可能重複寫入 | 同一個 `StreamsProducer`，可納入同一個 transaction |
| Error headers | 要自己補、自己命名、自己維護 | 框架自動附上 `__streams.errors.*` |
| 程式碼量 | topology、handler、headers、producer lifecycle 都要自己顧 | 一行 config 就能啟用主流程 |

如果把前面講的內容濃縮成一句話，Kafka 4.2.0 以前的 DLQ 比較像 application 自己補出來的機制；到了 KIP-1034 之後，DLQ 才真正變成 Kafka Streams 內建、而且能和 transaction 模型一起運作的能力。

## 用測試看行為差異，比講概念更準

這裡補充一點：很多細節放在測試裡看會更直接，因為 input、output、DLQ record 和 headers 都能在同一個地方觀察。若想對照本文提到的 routing 與 header 行為，直接看 `before/` 和 `after/` 的測試會很快。

如果你想自己跑一次範例，專案目錄在：

```bash
cd examples/kafka/kip-1034-dlq-blog-post
./gradlew test
```

## 什麼時候你會真的想升到 4.2.0？

如果你有下面幾種需求，KIP-1034 的價值就會很明顯：

- 你希望 deserialization error 也能穩定進 DLQ。
- 你不想在 topology 外再養一把 producer。
- 你有開 `exactly_once_v2`，不想承擔 transaction 外送出造成的重複寫入風險。
- 你想要一致的 error headers，方便後續告警、查錯、回補。

換句話說，4.2.0 之後，DLQ 不再是「自己土法煉鋼補一套」，而是 Kafka Streams 終於把這件事做成框架責任。

## 結語

KIP-1034 這個功能我覺得很實用，因為它剛好補在一個很常痛、又很難自己補漂亮的地方。

在 Kafka 4.2.0 以前，DLQ 不是做不到，但你得自己處理很多框架本來不知道的事：錯誤發生在哪一層、怎麼送出去、headers 怎麼補、transaction 怎麼對齊。只要其中一塊沒顧好，整套方案就容易長成半套。

到了 Kafka 4.2.0，事情終於簡單很多。你把 `StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG` 設好，搭配 `LogAndContinueExceptionHandler`，就能讓 DLQ 走進 Kafka Streams 自己的處理流程。這也不只是語法糖，原本散落在 application 層的責任，現在終於回到 Kafka Streams 內部。

如果你現在正好有 Kafka Streams 應用卡在手動錯誤處理，這一版很值得看一下。

參考資料：

- [KIP-1034: Dead letter queue in Kafka Streams](https://cwiki.apache.org/confluence/display/KAFKA/KIP-1034%3A+Dead+letter+queue+in+Kafka+Streams)
