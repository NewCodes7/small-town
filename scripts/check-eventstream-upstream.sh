#!/usr/bin/env bash
set -euo pipefail

readonly PINNED_VERSION='1.0.1'
readonly PINNED_SOURCE_SHA256='67fd69b944e38b87e3074bc2df0239542a1171a24771c5524dd398285dab5c0b'
readonly PINNED_UPSTREAM_COMMIT='1e76ef478f0108b38e2d7b70b598b4e5f0def3d1'
readonly METADATA_URL='https://repo1.maven.org/maven2/software/amazon/eventstream/eventstream/maven-metadata.xml'
readonly DECODER_URL="https://raw.githubusercontent.com/awslabs/aws-eventstream-java/${PINNED_UPSTREAM_COMMIT}/src/main/java/software/amazon/eventstream/MessageDecoder.java"
readonly SDK_TRANSFORMER_URL='https://raw.githubusercontent.com/aws/aws-sdk-java-v2/master/core/aws-core/src/main/java/software/amazon/awssdk/awscore/eventstream/EventStreamAsyncResponseTransformer.java'

latest_version=$(curl --fail --silent --show-error --location "$METADATA_URL" \
    | sed -n 's:.*<release>\([^<]*\)</release>.*:\1:p')
if [[ "$latest_version" != "$PINNED_VERSION" ]]; then
    echo "A new software.amazon.eventstream:eventstream release exists: ${latest_version} (pinned: ${PINNED_VERSION})" >&2
    exit 1
fi

actual_source_sha256=$(curl --fail --silent --show-error --location "$DECODER_URL" | sha256sum | cut -d' ' -f1)
if [[ "$actual_source_sha256" != "$PINNED_SOURCE_SHA256" ]]; then
    echo "Pinned upstream MessageDecoder no longer matches SHA-256 ${PINNED_SOURCE_SHA256}" >&2
    exit 1
fi

sdk_transformer=$(curl --fail --silent --show-error --location "$SDK_TRANSFORMER_URL")
if ! grep -Fq 'private final MessageDecoder decoder = new MessageDecoder();' <<< "$sdk_transformer"; then
    echo 'AWS SDK EventStream decoder construction changed; inspect for an official configurable/lazy decoder path.' >&2
    exit 1
fi

echo "No upstream EventStream change detected (eventstream ${PINNED_VERSION}; SDK still hard-codes MessageDecoder())."
