# Kafka KIP-1034 DLQ example

This example now has three test layers in `before/`:

- `ClickEventManualDlqTopologyTest`: topology-level test via `TopologyTestDriver`
- `ManualDlqHandlerTest`: handler-level unit test
- `ManualDlqHandlerIntegrationTest`: real Kafka integration test via Testcontainers

## Run the integration test

From this directory:

```bash
./gradlew :before:test --tests io.example.ManualDlqHandlerIntegrationTest
```

The test prints these values at startup so you can inspect the broker afterward:

- `bootstrap.servers`
- `inputTopic`
- `outputTopic`
- `dlqTopic`

Topic names are intentionally stable and include the test class suffix:

- `it-click-events-manual-dlq-handler-integration-test`
- `it-click-events-output-manual-dlq-handler-integration-test`
- `it-click-events-dlq-manual-dlq-handler-integration-test`

The test deletes and recreates these topics at the start of each run, so the names can be reused safely.

## Keep the Kafka container for post-mortem inspection

The integration test uses `withReuse(true)`, but Testcontainers only keeps the container alive if reuse is enabled in your user-level config:

```bash
echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
```

Or run the helper script in this directory:

```bash
./scripts/enable-testcontainers-reuse.sh
```

Important:

- This is a user-level Testcontainers setting, not a reliable project-level setting.
- Without that opt-in, Testcontainers may still clean up the container when the JVM exits.

## Existing docker-compose Kafka

This integration test does not use the `docker-compose.yml` Kafka broker.

- `docker-compose` demo broker typically uses `localhost:9092`
- Testcontainers uses its own broker and bootstrap address

So the two do not need to share ports or topics.
