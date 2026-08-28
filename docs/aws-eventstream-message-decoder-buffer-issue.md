# AWS issue draft: make `MessageDecoder` initial capacity configurable or lazy

Target repository: <https://github.com/aws/aws-sdk-java-v2/issues/new/choose>

## Title

`EventStream MessageDecoder eagerly allocates 2 MiB per concurrent stream with no configuration hook`

## Body

### Describe the issue

`software.amazon.eventstream.MessageDecoder` eagerly allocates a 2 MiB heap `ByteBuffer` in every constructor call. AWS SDK for Java v2's
`EventStreamAsyncResponseTransformer.SynchronousMessageDecoder` constructs one decoder per response stream and provides no injection or
configuration point.

For services that emit small, long-lived messages, such as Amazon Bedrock `ConverseStream`, nearly all of this allocation remains unused for
the lifetime of every concurrent stream.

### Observed impact

With 45 concurrent Bedrock streams on a 512 MiB JVM heap, JFR attributed 41 live `byte[2097152]` arrays to the decoder constructor. Replacing
only the initial capacity with 32 KiB produced the following 20-minute load-test result:

| Metric | Before | After |
|---|---:|---:|
| Live set | 342 MiB | 250 MiB |
| Committed heap | 498 MiB | 310 MiB |
| Live heap per stream | 3.60 MiB | 1.87 MiB |

Throughput, first-token latency, and error count remained within the previous range. Messages larger than the initial capacity continued to
work because `MessageDecoder.feed` already reallocates to `Prelude.getTotalLength()`.

### Requested change

Please consider one of the following, in preference order:

1. Allocate only enough bytes for the prelude, then allocate the exact message length after decoding it.
2. Expose an initial-capacity constructor/configuration through `EventStreamAsyncResponseTransformer`.
3. Reduce the default initial capacity while retaining the existing grow-as-needed behavior.

An official path would avoid applications having to classpath-shadow `software.amazon.eventstream.MessageDecoder`, which also makes upstream
compatibility and security maintenance harder.

### Environment

- AWS SDK for Java v2: 2.33.5
- `software.amazon.eventstream:eventstream`: 1.0.1
- API: Amazon Bedrock Runtime `ConverseStream`
- Java: 26
