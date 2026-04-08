---
title: "Kafka 4.2.0 KIP-1034：內建 DLQ，終結手動錯誤處理"
slug: kafka-kip-1034-dlq
authors: [unknowntpo]
tags: [Kafka, Kafka Streams, DLQ]
---

本篇搭配的範例程式已一併放進這個 repo：`examples/kafka/kip-1034-dlq-blog-post/`。

`before/` 對應 Kafka 4.2.0 以前常見的手動 DLQ 作法，`after/` 對應 Kafka 4.2.0 / KIP-1034 的內建 DLQ 作法。

如果你平常有在跑 Kafka Streams，應該多少都遇過這種情況：正常資料一路順順地進來，直到某一筆髒資料出現，整個 flow 就開始變得很尷尬。你當然可以選擇直接 fail，讓程式停掉；也可以選擇 continue，把壞資料跳過。但實務上通常還有第三個需求，就是把那筆壞資料留下來，丟到另一個 topic，之後再補救、重送，或至少做告警。這就是 DLQ（Dead Letter Queue）存在的理由。

問題是，在 Kafka Streams 4.2.0 以前，這件事沒有你想像中那麼直覺。你可以做，但往往要自己補很多東西，而且一不小心就會踩到 transaction 邊界，特別是你開了 `exactly_once_v2` 之後，事情會更麻煩。

Kafka 4.2.0 的 KIP-1034，就是把這一塊補起來。它不是只幫你少寫幾行 code 而已，而是把「送 DLQ」這件事正式拉回 Kafka Streams 自己的 transaction flow 裡面。這篇我想用 repo 裡的範例，從舊作法一路走到新作法，直接看差別到底在哪裡。

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

這也是為什麼 DLQ 在 Kafka Streams 裡，不只是「catch exception 然後送另一個 topic」這麼簡單。你得先分清楚，錯誤是發生在 topology 裡，還是發生在 topology 之前。

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

## Kafka 4.2.0 以前：手動做 DLQ，得分成兩種錯誤處理

先講 Kafka 4.2.0 以前，實務上常見的手動 DLQ 作法。這類做法大致會分成兩條路，repo 裡的 `before/` 只是把它們整理成可重現的範例，方便對照它們各自的限制。

### 路線一：processing error 發生在 topology 裡

如果錯誤是出現在 topology 內部，你還有很多地方可以攔。最直覺的做法就是在 operator 裡自己 `try/catch`。

這段是 `ClickEventManualDlqTopology.java` 的核心：

```java
stream
    .flatMap((key, value) -> {
        try {
            ClickEvent event = MAPPER.readValue(value, ClickEvent.class);
            String processed = "user=" + key + " clicked ad=" + event.adId + " count=" + event.count;
            return Collections.singletonList(KeyValue.pair(key, processed));
        } catch (Exception e) {
            sendToDlq(key, value, e);
            return Collections.emptyList();
        }
    })
    .to(outputTopic);
```

這段 code 要看的重點不在 `flatMap`，而是在 `catch` 裡那個 `sendToDlq()`。舊版最常見的做法，就是自己準備一個獨立的 `KafkaProducer`，遇到壞資料就直接送去 DLQ。

看起來很合理，因為它真的能動，而且你也可以順手把 error metadata 補上去：

```java
ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(dlqTopic, key, value);
dlqRecord.headers().add("error.message", cause.getMessage() != null
        ? cause.getMessage().getBytes() : "null".getBytes());
dlqRecord.headers().add("error.class", cause.getClass().getName().getBytes());
dlqProducer.send(dlqRecord).get();
```

問題是，這個 `dlqProducer` 跟 Kafka Streams 內部那個 producer 是兩回事。你現在只是「在 topology 裡面另外開一把槍」，不是讓 Streams 幫你送。當需求只停在「有送出去就好」時，很多團隊做到這裡就收工了。但如果你有開 EOS，這種寫法就開始有風險。

### 路線二：deserialization error 發生在 topology 之前

更麻煩的是 deserialization error。

這種錯誤不是發生在 `map`、`flatMap`、`transform` 那些步驟裡，而是在 record 被 consumer 撈回來之後、真正進入 topology 之前就發生了。換句話說，processor 還沒拿到資料，你就已經炸掉了。

這種情況下，你在 topology 裡做任何分流都沒用。`flatMap` 幫不上忙，`split()` 幫不上忙，`branch()` 也幫不上忙。你唯一能掛的點，是 `DeserializationExceptionHandler`。

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

這邊的尷尬點更直接。因為你已經不在 topology 裡了，所以沒有 `context.forward()` 可以用，也沒有什麼內建機制能讓你把一筆 DLQ record 回交給 Kafka Streams。要送出去，還是只能自己建一個獨立的 `KafkaProducer`。

而且不只要自己送，你還得自己複製原始 headers、自己補 topic / partition / offset / exception 這些 metadata：

```java
record.headers().forEach(h -> dlqRecord.headers().add(h));
dlqRecord.headers().add("__manual.error.topic", record.topic().getBytes());
dlqRecord.headers().add("__manual.error.partition",
        String.valueOf(record.partition()).getBytes());
dlqRecord.headers().add("__manual.error.offset",
        String.valueOf(record.offset()).getBytes());
```

這就是舊版最麻煩的地方：不只是「麻煩寫」，而是不同錯誤階段還要用不同招式補洞。

把 `before/` 這兩條路放在一起看，痛點其實很明確，而且每一個都很實務：

- 你得自己決定錯誤該在哪一層攔，processing error 跟 deserialization error 還不是同一套寫法。
- 你得自己養一把額外的 `KafkaProducer`，包含 lifecycle、設定、送出失敗時怎麼處理。
- 你得自己定義 headers，要帶哪些 metadata、命名規則怎麼訂，全都要自己維護。
- 你得自己承擔 tx-safe 的風險，尤其在 EOS 打開時，DLQ 重複寫入不是理論問題，而是真的可能在 retry 時出現。
- 你得自己測，連測試都會被迫圍繞 workaround 來寫，而不是直接驗證框架行為。

換句話說，舊版的問題不是「程式有點醜」。而是你為了補一個框架沒內建的能力，最後會把錯誤處理、資料一致性、甚至 observability 的責任一起扛到 application 層。

## 真正的痛點不是 code 醜，而是不 tx-safe

很多人第一次看這題，會以為重點只是程式碼比較囉唆。其實不是。真正麻煩的是 transaction 邊界。

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

如果你在這個過程中，另外拿一個獨立的 `KafkaProducer` 去送 DLQ，那筆 DLQ record 根本不在同一個 transaction 裡。

用圖看會更清楚：

```text
Kafka Streams TX

+----------- begin transaction -----------+
| consume -> deserialize -> process       |
|                           |             |
|                           +-> output    |
|                               (in TX)   |
+------------- commit / abort ------------+

manual DLQ producer

process error / handler
        |
        +-> dlqProducer.send()
            (outside TX)
```

也就是說，可能會發生這種事：

1. 你的手動 DLQ producer 已經把壞資料送出去了。
2. 但 Streams 自己那個 transaction 後面因為 rebalance、crash，或其他原因 abort。
3. Streams retry 之後，又重新處理同一筆資料。
4. 你的 DLQ 又被送一次。

於是你得到的不是「可靠的錯誤收容」，而是「可能重複寫入的錯誤收容」。

這一點在 `before/` 裡其實寫得很清楚。無論是 `ClickEventManualDlqTopology.java` 還是 `ManualDlqHandler.java`，class comment 都在強調同一件事：只要是獨立 producer，就不在 Streams 的 transaction 內，abort 之後沒辦法跟著 rollback。

如果用時序來看，`before` 大概是這樣：

```text
Streams                 Manual DLQ Producer             Kafka
   |                           |                        |
   | begin TX                  |                        |
   | process bad record        |                        |
   |-------------------------->| send DLQ               |
   |                           |----------------------->|
   |                           |      DLQ committed     |
   | abort TX                  |                        |
   | retry same record         |                        |
   |-------------------------->| send DLQ again         |
   |                           |----------------------->|
```

所以舊版真正卡住的點，不是少一個 helper method，而是框架本身沒有給你一條正式、內建、可 tx-safe 的 DLQ 路。

## Kafka 4.2.0 / KIP-1034：框架終於把這條路補起來

到了 Kafka 4.2.0，KIP-1034 把這件事正式拉進 Kafka Streams 的 error handling flow。

簡單講，新的方向是：exception handler 不再只能回答「繼續」或「失敗」，它還可以把要送去 DLQ 的 records 回交給框架，讓框架自己送。

這裡的改變很關鍵，因為一旦是框架自己送，就能走它原本那套 producer、原本那個 transaction，而不是你另外繞出去開一個獨立 producer。

`after/` 的範例特別值得看，因為它的 topology 幾乎乾淨到不像在處理 DLQ：

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

這就是 KIP-1034 最重要的差別。它不是只幫你省掉自己建 `ProducerRecord` 的麻煩，而是把 DLQ 重新納入 Kafka Streams 的一致性模型裡。

如果把 `after` 帶來的方便功能拆開看，大概可以整理成這幾件事：

- 你不用再手動建 DLQ producer，framework 直接幫你送。
- 你不用再把 DLQ 邏輯塞進 topology，主流程可以維持乾淨。
- 你不用再自己補一堆 error headers，常用的 exception / topic / partition / offset 都會自動帶上。
- 你不用再為 deserialization error 另外發明一套 workaround，框架已經有正式路徑可以處理。
- 你比較容易把 DLQ 跟 `exactly_once_v2` 放在一起思考，因為現在它終於回到同一個 transaction 模型裡。

對日常維運來說，這些改變的價值很直接：設定比較少、拓樸比較乾淨、錯誤資訊比較完整、測試比較好寫，最重要的是整體行為比較能預期。

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
|                    +-> DLQ record via framework          |
|                    +-> output record via framework       |
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

這點在 `ClickEventTopologyTest.java` 也有直接驗證。測試不是只檢查「壞資料有沒有進 DLQ」，而是把 DLQ record 讀出來，確認這幾個 header 都真的存在。

```java
assertTrue(headerNames.contains("__streams.errors.exception"));
assertTrue(headerNames.contains("__streams.errors.message"));
assertTrue(headerNames.contains("__streams.errors.stacktrace"));
assertTrue(headerNames.contains("__streams.errors.topic"));
assertTrue(headerNames.contains("__streams.errors.partition"));
assertTrue(headerNames.contains("__streams.errors.offset"));
```

這個細節很重要，因為實務上 DLQ 不是只是拿來「丟垃圾」而已。你後面通常還要查原因、做告警、回補資料，甚至把壞資料拿去做 replay。這些 metadata 如果框架能穩定幫你補，後面的處理會省事很多。

## Before / After 直接比一次

| 項目 | Kafka 4.2.0 以前的手動作法 | Kafka 4.2.0 / KIP-1034 |
| --- | --- | --- |
| Processing error | 可以自己攔，但通常得自己把錯誤導去 DLQ | 可延續框架內建處理方式 |
| Deserialization error | 只能掛 `DeserializationExceptionHandler`，而且只能自己送 | 可直接透過內建 DLQ flow 處理 |
| DLQ 寫入方式 | 常見是獨立 `KafkaProducer`，責任全在 application | 由 Kafka Streams 內部送出 |
| Tx-safe | 很容易踩到 transaction 外送出，retry 時可能重複寫入 | 同一個 `StreamsProducer`，可納入同一個 transaction |
| Error headers | 要自己補、自己命名、自己維護 | 框架自動附上 `__streams.errors.*` |
| 程式碼量 | topology、handler、headers、producer lifecycle 都要自己顧 | 一行 config 就能啟用主流程 |

如果你只看表面，會覺得差別是「少寫很多 code」。但從系統設計角度看，更準確的說法是：舊版是 application 自己硬補，4.2.0 才是 framework 正式承認 DLQ 是 Streams 的一級能力。

## 用測試看行為差異，比講概念更準

這份範例我很喜歡的一點，是它沒有要求你一定要先起 broker 才能理解行為。`before` 跟 `after` 都有用 `TopologyTestDriver` 寫測試，所以很多事情可以直接在單元測試層驗證。

### `before` 測的是什麼

`ClickEventManualDlqTopologyTest` 主要驗證三件事：

1. 正常資料會進 output topic。
2. 壞資料不會進 output，而是呼叫手動的 DLQ producer。
3. valid / invalid 混在一起時，路由結果是否正確。

它用 `@Mock KafkaProducer` 加 `ArgumentCaptor` 去抓那筆手動送出的 DLQ record。這個測試其實也反映了舊作法的本質：你要驗證 DLQ，就得 mock 你自己額外建出來的 producer。

### `after` 測的是什麼

`ClickEventTopologyTest` 的重點就乾淨很多。它直接在 config 裡打開：

```java
props.put(StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG, DLQ_TOPIC);
props.put(StreamsConfig.DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
        LogAndContinueExceptionHandler.class);
```

接著測四種情況：

1. valid record 正常進 output。
2. invalid record 進 DLQ。
3. DLQ record 真的帶有 `__streams.errors.*` headers。
4. mixed records 會同時正確流向 output 和 DLQ。

這裡的差別很能說明 KIP-1034 的價值。新版測試在驗證的是「框架行為」，不是「我自己額外包出來的 workaround 有沒有剛好送成功」。

如果你想自己跑一次範例，專案目錄在：

```bash
cd examples/kafka/kip-1034-dlq-blog-post
./gradlew test
```

## 什麼時候你會真的想升到 4.2.0？

如果你的場景只是 demo，或資料真的很乾淨，舊版手動 DLQ 不是完全不能用。但只要你有下面幾種需求，KIP-1034 的價值就會很明顯：

- 你希望 deserialization error 也能穩定進 DLQ。
- 你不想在 topology 外再養一把 producer。
- 你有開 `exactly_once_v2`，不想承擔 transaction 外送出造成的重複寫入風險。
- 你想要一致的 error headers，方便後續告警、查錯、回補。

換句話說，4.2.0 之後，DLQ 不再是「自己土法煉鋼補一套」，而是 Kafka Streams 終於把這件事做成框架責任。

## 結語

KIP-1034 這個功能我覺得很實用，原因不是它多炫，而是它剛好補在一個很常痛、又很難自己補漂亮的地方。

在 Kafka 4.2.0 以前，你不是不能做 DLQ，而是你得自己處理很多框架本來不知道的事：錯誤發生在哪一層、怎麼送出去、headers 怎麼補、transaction 怎麼對齊。只要其中一塊沒顧好，整套方案就容易長成半套。

到了 Kafka 4.2.0，事情終於簡單很多。你把 `StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG` 設好，搭配 `LogAndContinueExceptionHandler`，就能讓 DLQ 走進 Kafka Streams 自己的處理流程。這不是語法糖，而是把原本散落在 application 層的責任，收回到 framework 裡。

如果你現在正好有 Kafka Streams 應用卡在手動錯誤處理，這一版很值得看一下。

參考資料：

- [KIP-1034: Dead letter queue in Kafka Streams](https://cwiki.apache.org/confluence/display/KAFKA/KIP-1034%3A+Dead+letter+queue+in+Kafka+Streams)
