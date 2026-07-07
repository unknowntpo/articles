---
title: "Choosing the Version at Communication Time: How Kafka Picks a Wire Protocol Version"
slug: kafka-version-negotiation
authors: [unknowntpo]
tags: [Kafka, Protocol, Versioning]
---

> **Version Control on Top of Tens of Thousands of Nodes · Part 3**　｜　Long-lived clients, rolling-upgraded brokers, and how they agree on a common version.
>
> This article focuses on **which version actually gets chosen at communication time**: how a single connection decides which wire protocol version to speak, what role `metadata.version` (MV) plays here, and what error you see when no version can be negotiated.
>
> The topic is split into a series; adjacent parts each stand alone. This one only recaps prerequisites when needed:
>
> - *Kafka Cluster Version Definition & KIP-1170*: release version (the ceiling and the rules) vs `metadata.version` (the cluster's currently-enabled scope); how MV drives record schemas, feature gates, and startup checks.
> - *Runtime Version Up/Down* (Ming-Yen's talk): `kafka-features` finalize validation, feature↔MV dependencies and downgrade rules, fenced brokers blocking upgrades — the "at the moment of up/down" behaviors.
>
> The body focuses on the mechanism; the corresponding source snippets and file:line references live in [Appendix A: Source Reference](#appendix-a). Reference commit: trunk `b7b1c0a8`.

---

## Part 1 — Why versions are inherently misaligned, and how one gets chosen

### 1. Motivation: why versions are inherently misaligned and can only be negotiated at connect time

When people talk about a "Kafka version," they usually have a single number in mind (say 3.6 or 4.1) and an implicit assumption: **client and broker are on the same version and upgrade together** — "one version to rule them all." That holds on a single box or in a test setup, but in a production cluster two realities break it.

First, **clients are long-lived**. The broker is maintained by the platform team and gets upgraded; but the client is a library embedded in each application — some years-old batch job, some legacy service nobody dares touch — and may still be running an ancient kafka-client today. Upgrading every application's client on the same day is basically impossible. This is not hand-waving: Apache Kafka kept *every single* protocol API version from 0.8.0 (2013) through 3.x — nine full years — precisely because there was always some old client still connecting; only 4.0 ([KIP-896](https://cwiki.apache.org/confluence/display/KAFKA/KIP-896%3A+Remove+old+client+protocol+API+versions+in+Kafka+4.0)) raised the baseline to about 2.1 (2018).

Second, **brokers are upgraded one at a time (rolling), so mixed versions are unavoidable during the process**. You upgrade brokers one by one: take one down, swap the binary, bring it back, move to the next. Throughout, the cluster inevitably has "new and old brokers mixed together," and clients are connected to both. To avoid downtime, you cannot require the whole cluster to be on the same version.

The causal chain collapses to one line: at any instant, different nodes necessarily have different binary capabilities → to stay up you cannot pin the whole cluster to one version → versions cannot be aligned ahead of time and can only be decided **at connect time** by the two parties. That is where this talk's topic comes from. Later we introduce three communication roles (client↔broker, broker↔controller, broker↔broker); to be clear up front: those are *where version selection happens*, not the *cause* of misalignment — the cause is the two realities above.

A preview of this choice's bill: splitting versioning down to a per-API `[min, max]` range makes the protocol surface, the compatibility test matrix, and the implementation burden on non-Java clients all scale with the number of APIs. MongoDB and PostgreSQL face the same problem and choose to maintain a single global protocol version number, trading fine-grained evolution for simplicity. What Kafka buys is per-API independent evolution — you don't have to bump the whole package. That cost eventually grew large enough to need [KIP-482](https://cwiki.apache.org/confluence/display/KAFKA/KIP-482%3A+The+Kafka+Protocol+should+Support+Optional+Tagged+Fields) flexible versions as a relief valve — once an API enters a flexible version, subsequent optional tagged fields no longer need a version bump. Verdict: for an ecosystem where the client is implemented by many third parties and there are dozens of APIs, this bill is worth paying; but it is only worth it in that kind of ecosystem, not as a universal answer.

### 2. Terminology: when we say "version," which layer do we mean?

Before the mechanism, let's disambiguate. The everyday word "version" actually conflates three "versions" with different scopes and different times-of-change:

```text
release version        which binary this node runs      per-node (ops swaps binaries, rolling)
metadata.version       the level the whole cluster currently enables   cluster-wide (admin finalizes manually, deliberately decoupled from swapping binaries)
wire protocol API ver  which version this connection actually speaks    per-connection (runtime, each connection decides on its own)
```

- **release version**: which binary this node has installed. Per-node; ops swaps the binary, rolling.
- **`metadata.version`**: the feature level finalized for the cluster. "Finalize" means the admin manually declares the feature level the whole cluster uniformly adopts; *how* it is declared is the subject of Part 1 of the series — here we only need to know it is a single cluster-consensus value. Cluster-wide, and deliberately decoupled from swapping binaries — upgrading a binary does not mean MV moves with it.
- **wire protocol API version**: which version this connection actually speaks. Per-connection; runtime, each connection decides on its own.

These three are independent axes that do not change together automatically: you can upgrade the binary (release version) but not yet finalize `metadata.version`; two brokers can be on the same binary yet behave differently because a connection selected a different wire protocol version. The protagonist here is the third layer — the wire protocol API version.

### 3. Architecture (a): three communication roles; to negotiate, first send `ApiVersionsRequest`

The source of the wire version is one lookup: **after a connection is established, the initiator first sends an `ApiVersionsRequest`, asking the peer "which version range does each of your APIs support?"**, and the peer replies with the `[min, max]` range for each API. This mechanism was introduced by [KIP-35](https://cwiki.apache.org/confluence/display/KAFKA/KIP-35+-+Retrieving+protocol+version) and is the prerequisite for everything downstream.

The lookup has a cost: every new connection pays one extra round trip — `NetworkClient` queues the new connection into the `ApiVersionsRequest` flow and only treats it as ready after the reply (`NetworkClient.java:1106` / `:1122`). What you get back is that every subsequent request no longer has to guess a version: a one-time cost, amortized over the long run.

A Kafka cluster has three communication roles (the RPCs listed per row are representative, not exhaustive). Most paths do this lookup after connecting; the only one that does not is **partition replication** (follower→leader, the component being the replica fetcher) — reason in the next section:

```text
client ↔ broker       read/write data, query metadata     Produce, Fetch, Metadata…
broker ↔ controller   registration, heartbeat, forward admin   BrokerRegistration, BrokerHeartbeat…
broker ↔ broker       partition replication / txn markers   Fetch, ListOffsets / WriteTxnMarkers…
```

Note that broker↔broker is not monolithic: **partition replication** (follower→leader) does not do the lookup, but **transaction markers (`WriteTxnMarkers`), also broker↔broker, still negotiate per KIP-35** (`discoverBrokerVersions=true`). So "does not look up" qualifies the partition-replication flow specifically, not the entire broker↔broker role.

The lookup only settles "knowing what the peer will speak"; once the ranges are known, **who decides the final version differs by path** — the subject of the next section.

### 4. Architecture (b): after the lookup, who says which version wins?

First the observed behavior per path:

- **client ↔ broker: negotiate.** For ordinary producer / consumer / admin APIs, take the highest version in the intersection of both ranges (`NodeApiVersions.latestUsableVersion`). The `MetadataVersion` javadoc even spells it out: "when communicating with clients, the client decides on the API version."
- **broker ↔ controller: also negotiate.** When a broker sends `BrokerHeartbeat`, `BrokerRegistration`, etc. to the controller, it plays the client role — same "highest common version," not decided by MV.
- **partition replication (follower→leader): no negotiation.** On this flow, driven by the replica fetcher, the initiator does not look up the peer's versions (`discoverBrokerVersions=false`); the version is instead decided ahead of time by the finalized MV. This connection carries three non-negotiated RPCs in sequence — `OffsetsForLeaderEpoch` (align), `ListOffsets` (locate), `Fetch` (pull data); the representative `Fetch` version is decided directly by `fetchRequestVersion(MV)`, and if the leader does not support it, it fails outright, with no room to back off. (Transaction markers, also broker↔broker, do negotiate.)

The three behaviors converge on one mechanism: **the deciding power lies in the allowed version range declared by the code that builds the request**. When building a request, `AbstractRequest.Builder` carries a `[oldestAllowedVersion, latestAllowedVersion]`; before sending, the layer below intersects it with the range the peer advertised via `ApiVersions`:

| Range the Builder gives | Effect |
| --- | --- |
| Full range `[oldest, latest]` | `ApiVersions` negotiation, pick the highest in the intersection |
| Pinned to `[MV, MV]` | Decided by MV (the replication path does no lookup and just sends that version; unsupported → `UnsupportedVersionException`) |
| Pinned to a fixed constant | Hard-coded; doesn't look at MV, no room to negotiate |

client↔broker and broker↔controller give the full range and land in the first row; the replication `Fetch` is pinned to `[MV, MV]` and lands in the second. The other two replication RPCs split more finely (`ListOffsets` uses MV as an upper bound; `OffsetsForLeaderEpoch` is a fixed constant) — an advanced detail, with the full table in [Appendix A3](#a3). The main message only needs one sentence: **the replication `Fetch` is decided by MV; everything else negotiates.**

#### Why does the replication path use MV while the rest negotiate?

The first question anyone asks upon seeing "the replication path doesn't negotiate" is: why not just negotiate like the other connections? The direct answer: **negotiation lets brokers *understand* each other's requests, but "understanding" does not equal "correct cross-version behavior."** Example: [KIP-903](https://cwiki.apache.org/confluence/display/KAFKA/KIP-903%3A+Replicas+with+stale+broker+epoch+should+not+be+allowed+to+join+the+ISR) adds `ReplicaState` (carrying a broker epoch) to `Fetch` v15 to block a follower with a stale epoch from joining the ISR — even if the leader can *read* an old `Fetch` (negotiation could downgrade to it), the missing field means it cannot block that. So what the replication path needs is not "both ends can talk," but "the whole cluster consistently enables the same version's behavior"; a finalized MV provides exactly that — an **operator-controlled, atomic switch decoupled from swapping binaries** — flipping the whole cluster to a specified version at once, which is also why an upgrade is two-phased. The other connections are point-to-point; each can just pick its best version.

One level deeper, "why this split" has three reasons (design reasoning, but each maps onto a verified mechanism):

1. **Bootstrap chicken-and-egg.** The finalized MV itself lives in the metadata log; a broker only learns MV by fetching that log from the controller. If the `Fetch` version used to fetch the log were decided by MV, you'd have a cycle — to know MV you must fetch the log, to fetch the log you must know MV. So startup RPCs (registration, the `Fetch` for the metadata log) cannot be gated by MV; they must use self-contained `ApiVersions` negotiation. Proof: KRaft's `Fetch` for the metadata log negotiates over the full range and ignores MV (see [Appendix A4](#a4)).
2. **Control plane and data plane have different consistency needs.** broker↔controller is point-to-point (a broker to the current controller); each connection picks its best version and need not be cluster-wide consistent. The replication data plane needs all followers↔leader to speak the same version — MV is that "centralized, one-shot" switch.
3. **Don't gate the channel to the authority using a value the authority hands out.** The controller is the source of MV; using "the MV it hands out" to decide the version of "the RPC that connects to it" is logically circular.

One common misconception to correct: this is **not** a "negotiate before the broker joins, switch to MV after" phase change. `BrokerHeartbeat` is sent every few seconds across the broker's entire lifetime and always negotiates. The real distinction is permanent, by RPC role: the control plane always negotiates; the replication data plane is always decided by MV.

#### The bill for MV's centralized decision, and a counterfactual

That centralized decision is not free; the bill has three items: **an upgrade goes from one step to two** (first roll the binaries, then finalize by hand); **one more human step you can forget** — forget to finalize, and replication keeps speaking the old version (`fetchRequestVersion`'s threshold table stays at the old value, and the upgrade guide explicitly lists finalize as a separate step after binaries are swapped); and **no room to back off** — if the leader doesn't support the MV-specified version, it just fails, unlike negotiation which can downgrade. To be clear, this "two-phase" is a general property of finalized-MV governance, not specific to replication: anything gated by MV (including metadata-log record versions) follows the same "roll binaries first, finalize later" rhythm.

To see why the bill is worth paying, the fastest route is the counterfactual: PostgreSQL's physical (streaming) replication ships raw WAL bytes, and the **WAL format is incompatible across major versions**, so its replication capability is hard-locked to a single major — meaning an HA replica **cannot roll across a major in place** and can only use `pg_upgrade` (fast but requires downtime). For a near-zero-downtime major upgrade you must switch to **logical replication** (which decodes WAL into row changes, independent of the physical format, and can cross majors) — blue/green, standing up a new-major cluster and failing over, but that is a bolt-on rather than built into the replication layer, and comes with limits like DDL / sequences not being replicated. As annoying as Kafka's two-phase upgrade is, what it buys is exactly what PostgreSQL's physical replication cannot give: **an online rolling upgrade of the replication layer itself.** I consider this the most defensible trade-off in the whole design. (MongoDB's `featureCompatibilityVersion` follows the same idea as MV; the three-way comparison is in [Appendix C](#appendix-c).)

One last line to avoid confusion: features (like `group.version`, `share.version`) **do not decide RPC versions** — only MV does that, for the replication `Fetch` / `ListOffsets`; feature and RPC version are two orthogonal axes (see [Appendix A5](#a5)).

### 5. Fetch as an example: the same broker speaks two versions at once

Let's ground the previous section in one concrete RPC. The same `Fetch` API has two identities, taking the client↔broker and broker↔broker version-selection paths respectively:

```text
consumer fetch   Kafka 2.4 client (validVersions 0-11)   intersect with 4.1 broker → Fetch v11   ← negotiated
replica  fetch   follower                                 version = fetchRequestVersion(MV) → v17  ← decided by finalized MV
```

So the same 4.1 broker will **simultaneously** negotiate Fetch v11 with a Kafka 2.4 old client (whose `validVersions` is 0-11, taking the highest common version) and use v17 for a follower (decided by the finalized MV) — **the same release hosting multiple wire versions at once**. A single version number simply cannot express this.

An aside on the dual-role design itself: consumer and replica sharing one `Fetch` is a dividend of the abstraction "a replica is just another reader of the log" — the read path and fetch-session machinery are maintained once. The bill is that replica-specific semantics keep seeping into the shared schema: the `LogStartOffset` field is annotated "only used when a follower sends it" (`FetchRequest.json:103`); [KIP-903](https://cwiki.apache.org/confluence/display/KAFKA/KIP-903%3A+Replicas+with+stale+broker+epoch+should+not+be+allowed+to+join+the+ISR) then added `ReplicaState` in v15 for broker-epoch validation and deprecated the flat `ReplicaId`. Collect the abstraction dividend first, pay the semantic rent slowly.

> **Quiz 1**: how is the `Fetch` version for a replica fetch (broker↔broker) decided? (Answer in [Appendix B](#appendix-b).)

---

## Part 2 — What error will you see when it fails

This talk only covers the "can't negotiate a version at communication time" error. Errors at finalize / up-down time — `INVALID_UPDATE_VERSION` from `kafka-features upgrade`, a fenced broker blocking feature/MV upgrades, feature↔MV dependencies and downgrade rules — belong to *Runtime Version Up/Down* in the same series and are only mentioned here in passing.

### 6. No version intersection: the client aborts locally

A client can't just use its own newest API version, because the broker may not support it. The rule is the highest common version:

```text
chosen version = max(intersection(client allowed range, broker supported range))
```

When the intersection is empty, `NodeApiVersions.latestUsableVersion(...)` throws `UnsupportedVersionException`; on receiving it, `NetworkClient` **skips the socket and doesn't send**, dropping the request into `abortedSends`, and finally producer / consumer / admin each hand the error back to the application when they see `response.versionMismatch()`. Key point: this kind of `UnsupportedVersionException` is often **not a response from the broker** but the client discovering, before sending, that there is no usable protocol version, and aborting locally.

```text
client allows Produce 0-13 , broker supports 0-10  -> chosen 10
client allows Produce 11-13, broker supports 0-10  -> UnsupportedVersionException (aborted before send)
```

### 7. Bypassing negotiation and sending an unsupported version: the broker closes the connection

If you bypass negotiation and send an API version the broker doesn't support, the failure path is a fixed one: the broker's `RequestContext` fails to parse the request → throws `UnsupportedVersionException` → `SocketServer` closes the connection outright (`RequestContext.java:112`, `SocketServer.scala:781`).

Why close the connection rather than return an error response? The broker can in fact read the header (it knows the apiKey and version), but serializing an ordinary response must be done at the client-specified request version — and since that version itself is unsupported, the broker cannot guarantee the response it produces would be correctly parsed by the peer. Closing the connection is the only reliable action.

The one exception is `ApiVersions` itself: it is the bootstrap escape hatch — even if the client sends an `ApiVersions` version beyond the broker's supported range, the broker will not close the connection but returns a v0 response carrying the `UNSUPPORTED_VERSION` error code and its own supported version range, letting the client recover and re-negotiate.

> **Quiz 2**: what happens if a client bypasses negotiation and sends a version the broker doesn't support? (Answer in [Appendix B](#appendix-b).)

### 8. Version truncation: why "bumping a little" isn't enough

The most easily overlooked root cause of Section 6's "empty intersection" is that **old wire protocol API versions get removed entirely**. Each API has its own `validVersions` range, and that range is not guaranteed to always start at `0`.

Kafka 4.0 removed a batch of old wire API versions — for example `FetchRequest.json`'s `validVersions` is already `"4-18"` (Fetch v0–v3 removed, min raised to 4). So if a client is too old and only speaks a truncated version, it falls outside the intersection — negotiation directly yields no usable version. Here "bump a little more" doesn't help; you must cross the upgrade guide's version floor (before moving either end to 4.0, the other end must be ≥ 2.1 — a bidirectional requirement).

Worth pausing to look at the shape of this decision. From 0.8.0 (2013) through 3.x, Kafka kept every protocol API version for a full nine years — a "compatibility above all" trade-off pushed to the extreme: the upside is any old client can always connect; the cost is that every old version is a live code path and test-matrix cell, and the broker can never assume the client has any newer capability. 4.0 used KIP-896 to pull the baseline to 2.1 (2018), essentially a one-time reconciliation of the books: trading "cutting off pre-2018 clients" for "deleting code, shrinking the test surface, and letting protocol assumptions move forward seven years." Why wait so long? Because cutting off clients is an irreversible breaking change, and only the social contract of a major-version boundary can afford it. My take: the right call, if anything a bit late — the nine-year window shows a project conservative about compatibility to the point of self-flagellation, and that conservatism is the key to understanding all of Kafka's version design.

### Recap

This talk's causal chain is a single line: clients are long-lived, brokers roll — versions are inherently misaligned — so each connection can only decide which version to speak at connect time — client↔broker and broker↔controller look up via `ApiVersionsRequest` and negotiate the intersection, while partition replication's `Fetch` is decided centrally by the finalized MV. The best proof that "one version to rule them all" fails is that 4.1 broker: **the same `Fetch`, at the same instant, speaking v11 to a Kafka 2.4 old client and v17 to a follower**. And when no version can be chosen: on an empty intersection the client aborts before sending; bypassing negotiation to send an unsupported version, the broker fails to parse and closes the connection. As for what errors appear at finalize / up-down time, that's for *Runtime Version Up/Down* in the same series.

One closing stance: **per-API fine-grained negotiation plus centralized governance of replication is a trade-off tailored for a system with an extremely fragmented client ecosystem and dozens of APIs** — worth it for Kafka, but not a universal answer. MongoDB chose a coarser global granularity; PostgreSQL simply gave up version governance in the replication layer — each fits its own ecosystem. Reading a system's version design is reading the assumptions it makes about its own users.

---

## Appendix A: Source Reference {#appendix-a}

> The body finished the mechanism; here are the corresponding source snippets and file:line. Reference commit: trunk `b7b1c0a8`.

### A1 — Builder version range and negotiation

- `AbstractRequest.Builder` carries `[oldestAllowedVersion, latestAllowedVersion]`; `NetworkClient` intersects before sending.
- The initiators of all four paths share the same `org.apache.kafka.clients.NetworkClient`, each with an `ApiVersions` cache — whether to send `ApiVersionsRequest` first is decided by the constructor parameter `discoverBrokerVersions`: true for the client, controller, and KRaft paths; false for the replica fetcher (an implementation detail, not expanded in the body).
- client↔broker takes the highest common version: `NodeApiVersions.latestUsableVersion(...)` (`clients/src/main/java/org/apache/kafka/clients/NodeApiVersions.java:149`).
- Doc: `server-common/src/main/java/org/apache/kafka/server/common/MetadataVersion.java:31` — "when communicating with clients, the client decides on the API version."

### A2 — broker ↔ controller (broker as client)

- `BrokerLifecycleManager.java:580`: `channelManager.sendRequest(new BrokerHeartbeatRequest.Builder(data), handler)`.
- `BrokerHeartbeatRequest.Builder` is just `super(ApiKeys.BROKER_HEARTBEAT)`, unpinned → full-range negotiation.
- Underlying: `NodeToControllerChannelManagerImpl.java:67` (`private final ApiVersions apiVersions`), `:115` / `:129` (`NetworkClient` takes `apiVersions`).
- Requests on this pipeline: `BrokerHeartbeat`, `BrokerRegistration`, `ControllerRegistration`, `AssignReplicasToDirs`.

### A3 — broker ↔ broker (replication) three-way split {#a3}

> The body only says "the replication `Fetch` is decided by MV, the rest negotiate"; the full version is three RPCs with three approaches (`core/src/main/scala/kafka/server/RemoteLeaderEndPoint.scala`). One term first: `ListOffsets` converts a timestamp / sentinel (earliest / latest / by-timestamp) into an offset (the consumer's `seekToBeginning`, `offsetsForTimes` rely on it; a follower uses it to find the truncation point).

| RPC | How the version is set | Evidence |
| --- | --- | --- |
| `Fetch` | MV **exact pin** (`[v, v]`) | `RemoteLeaderEndPoint.scala:215`; `FetchRequest.java:170-172` |
| `ListOffsets` | MV as **upper bound**, then negotiate (`[oldest, MV]`) | `RemoteLeaderEndPoint.scala:122`; `ListOffsetsRequest.java:88-90` |
| `OffsetsForLeaderEpoch` | **hard-coded constant v4**, not MV, not negotiated | `OffsetsForLeaderEpochRequest.java:60-65` (`Builder.forFollower(...)` → `new Builder((short)4, (short)4, data)`) |

- The underlying `BrokerBlockingSender.scala:82` is still a `NetworkClient`, but `:95`'s `discoverBrokerVersions=false` — it doesn't send `ApiVersionsRequest`, and sends directly at the MV-decided version.
- `MetadataVersion` has only two methods related to RPC versions: `fetchRequestVersion()` (`:273`) and `listOffsetRequestVersion()` (`:289`), affecting only the replication `Fetch` / `ListOffsets`.
- One reading: three RPCs on the same replication path with three selection approaches is not a design mistake. `OffsetsForLeaderEpoch`'s fixed v4 is a simplification left over after removing old `MetadataVersion`s (the result of the KAFKA-18465 cleanup); the code even states that if new versions are added in the future they should be gated behind `metadata.version` (comment at `OffsetsForLeaderEpochRequest.java:64`).

`MetadataVersion.fetchRequestVersion()` (`server-common/src/main/java/org/apache/kafka/server/common/MetadataVersion.java:273`):

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

### A4 — KRaft metadata-log Fetch: negotiation + a separate feature (advanced) {#a4}

> The "chicken-and-egg" proof from Section 4 lives here. The `Fetch` for the metadata log — a broker as observer, and controllers among the quorum — negotiates and ignores MV.

- `KafkaRaftClient.buildFetchRequest()` (`:2985`) → `RaftUtil.singletonFetchRequest(...)` → `KafkaNetworkChannel.buildRequest` (`:192`) wraps it as `FetchRequest.SimpleBuilder`.
- `SimpleBuilder` = `super(ApiKeys.FETCH)`, full range (`FetchRequest.java:133`) → `ApiVersions` negotiation, not MV.
- This path's capability is governed by the **`kraft.version` feature, independent of MV** (`KRaftVersion.java`); `KafkaRaftClient.java:185`'s `localSupportedKRaftVersion: SupportedVersionRange` is each node's self-reported supported range.

### A5 — feature and RPC are orthogonal; honor (advanced) {#a5}

> The body left just one line, "features don't decide RPC versions"; the full version follows. This section is adjacent to the *Version Definition* talk's feature gates, and is in the appendix to avoid stealing the body's main thread.

RPC version and feature are **two orthogonal axes; to use a capability you need both**:

- **RPC support (wire capability)**: whether the two ends "can speak" this RPC / this version — decided by binary capability + `ApiVersions` negotiation (MV for the replication path).
- **feature (cluster policy)**: whether the cluster "allows using" this capability — decided by the feature level finalized in the metadata log.

Key points:

- **A feature does not decide the RPC version.** Only MV does that, for the replication `Fetch` / `ListOffsets`; a feature produces a boolean capability or a record-format version.
- **What a feature actually gates is whether the broker will *honor* an RPC.** To honor means the broker, upon receiving a request, "accepts it and processes it per that capability's semantics," rather than rejecting, erroring, or ignoring. The RPC always exists in the binary and can be negotiated and delivered, but the broker checks the finalized feature to decide whether to honor: when `group.version` (KIP-848) is off, `handleConsumerGroupHeartbeat` fails outright (`KafkaApis.scala:2642-2650`); when `share.version` is off, `ShareFetch` is not accepted, and toggling it off clears the share session (`SharePartitionManager.java:633`, `KafkaApis.scala:4290`).
- **An externally-visible feature needs an RPC to carry it**: a new capability usually brings a new RPC (like `ConsumerGroupHeartbeat`, `ShareFetch` / `ShareAcknowledge`) or a higher version of an existing RPC. A purely internal feature needs no external RPC (it only changes a record format or internal behavior).
- **A versioned feature infers capability from the RPC version, in reverse**: `transactionVersionForAddPartitionsToTxn(request)` checks whether the request version > 3 → the client supports TV2 (`TransactionVersion.java:67`). The direction is opposite to "feature → RPC version."

### A6 — failure paths

Client-side intersection (`clients/src/main/java/org/apache/kafka/clients/NodeApiVersions.java:149`):

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

`NetworkClient.doSend(...)` on receiving `UnsupportedVersionException` skips the socket and drops it into `abortedSends` (called at `NetworkClient.java:591`, caught at `:597`); `NetworkClient.poll(...)` drains aborted sends (`:651` / `:940`). Producer / Consumer see `response.versionMismatch()` (`Sender.java:595`, `ConsumerNetworkClient.java:614`).

Broker-side path on receiving an unsupported version: `RequestContext` fails to parse the request and throws `UnsupportedVersionException` (`clients/src/main/java/org/apache/kafka/common/requests/RequestContext.java:112`) → `SocketServer` closes the connection (`core/src/main/scala/kafka/network/SocketServer.scala:781`).

The broker puts each API's min/max into the `ApiVersionsResponse` (`clients/src/main/java/org/apache/kafka/common/protocol/ApiKeys.java:287`); `ApiVersions` still advertises a v0 special case so it can be the bootstrap escape hatch. Schema evidence of truncation: `FetchRequest.json:61` = `"4-18"`, `ListOffsetsRequest.json:45` = `"1-11"`, `ProduceRequest.json` = `"3-13"`. The upgrade guide's note on the 4.0 truncation: `docs/getting-started/upgrade.md:229`.

---

## Appendix B: Common Misconceptions / Quiz {#appendix-b}

> Two questions matching the two main threads; each gives an "intuitive answer (usually wrong)" and the correct one — good for live Q&A.

### Q1: How is the `Fetch` version for a replica fetch (broker↔broker) decided?

- **Intuition**: the two brokers negotiate the intersection via `ApiVersions`.
- **Correct**: no negotiation. Decided by the finalized `metadata.version` (`fetchRequestVersion(MV)`, built as an `[v, v]` exact pin); all brokers derive the same version from the same MV.
- **Source**: `RemoteLeaderEndPoint.scala:215`, `MetadataVersion.java:273`

### Q2: What happens if a client bypasses negotiation and sends an API version the broker doesn't support?

- **Intuition**: the broker always returns an `UNSUPPORTED_VERSION` error code.
- **Correct**: for an ordinary API → `RequestContext` fails to parse, the broker throws `UnsupportedVersionException`, and `SocketServer` closes the connection. The one exception is `ApiVersions`: as the bootstrap escape hatch, it returns a v0 response carrying `UNSUPPORTED_VERSION` + the supported range so the client can recover.
- **Source**: `RequestContext.java:112`, `SocketServer.scala:781`, `ApiKeys.java` (the `ApiVersions` special case)

## Appendix C: How others answer the same question {#appendix-c}

> Full comparison (with source URLs) is elsewhere in the source notes; here is only the skeleton.

| | client↔server version | inter-node / replication-layer version governance |
| --- | --- | --- |
| **Kafka** | per-API `[min,max]` negotiation (finest granularity, largest protocol surface) | finalized `metadata.version` (manual finalize, decoupled from the binary) |
| **MongoDB** | global wire version (`hello` exchange) | `featureCompatibilityVersion` — same idea as MV |
| **PostgreSQL** | global protocol version (v3 unchanged for 20+ years) | **none** — physical replication locks the major (WAL incompatible across majors); an HA replica can't roll across a major in place; near-zero-downtime major upgrades require bolt-on logical replication |

Two takeaways: MongoDB's FCV shows that "decoupling the cluster capability generation from the binary and finalizing it manually" is not a Kafka quirk but a common answer for rolling upgrades in distributed systems; PostgreSQL is the counterfactual — the replication layer itself does no version governance, physical replication locks the major, and near-zero-downtime major upgrades can only lean on bolt-on logical replication rather than something built into the replication layer.
