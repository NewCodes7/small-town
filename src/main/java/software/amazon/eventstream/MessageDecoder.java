/*
 * Copyright 2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * ---------------------------------------------------------------------------
 * MODIFIED — software.amazon.eventstream:eventstream:1.0.1 의 동명 클래스를
 * 클래스패스 섀도잉으로 대체한다. 변경점은 INITIAL_BUFFER_SIZE 한 줄뿐이고
 * 디코딩 로직은 원본과 동일하다. 아래 「왜 포크했는가」 참고.
 * ---------------------------------------------------------------------------
 */
package software.amazon.eventstream;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * A simple decoder that accumulates chunks of bytes and emits eventstream
 * messages. Instances of this class are not thread-safe.
 *
 * <h2>왜 포크했는가</h2>
 *
 * 원본은 {@code INITIAL_BUFFER_SIZE = 2048 * 1024}(2 MiB)를 무인자 생성자에서 무조건 할당한다.
 * AWS SDK는 <b>ConverseStream 호출 하나당</b> 이 디코더를 새로 만든다
 * ({@code EventStreamAsyncResponseTransformer$SynchronousMessageDecoder}, 
 * {@code private final MessageDecoder decoder = new MessageDecoder()}).
 * 즉 <b>동시 스트림 1개당 2 MiB가 고정 비용</b>이다.
 *
 * <p>2026-08-27 JFR 실측(결과 문서 9장): 동시 45 스트림에서 {@code OldObjectSample} 164개 중
 * <b>{@code byte[2097152]}가 41개</b>로 압도적이었고, 할당 스택이 전부 이 클래스의 생성자였다.
 * 스트림당 live 힙 2.6~3.6 MB 중 <b>2.0 MB가 이 버퍼 하나</b>였다.
 *
 * <p>그런데 Bedrock ConverseStream의 메시지는 프렐류드 12B + 헤더 약 100B + JSON 페이로드
 * 수십~수백 B로 <b>보통 250B 남짓</b>이다. 버퍼의 99.99%가 쓰이지 않는다.
 *
 * <h2>왜 이 방법인가 — 다른 길이 전부 막혀 있다</h2>
 * <ul>
 *   <li>SDK 설정 노브: <b>없다</b>. aws-core/sdk-core 전수 검색 결과 eventstream 버퍼 관련
 *       설정이 존재하지 않는다</li>
 *   <li>주입 지점: <b>없다</b>. {@code SynchronousMessageDecoder}는 {@code private static final}
 *       내부 클래스이고 {@code new MessageDecoder()}가 하드코딩돼 있다</li>
 *   <li>버전 업그레이드: <b>불가</b>. Maven Central에 1.0.0과 1.0.1뿐이고 1.0.1이 최신이다</li>
 *   <li>크기 인자 생성자 {@code MessageDecoder(Consumer, int)}는 존재하지만 package-private에
 *       "To be used by tests only"라 SDK가 쓰지 않는다</li>
 * </ul>
 *
 * <p>Spring Boot 실행 가능 jar는 {@code BOOT-INF/classes/}를 {@code BOOT-INF/lib/*.jar}보다
 * 먼저 탐색하므로, 같은 FQCN을 소스에 두면 이 클래스가 라이브러리 것을 이긴다.
 *
 * <h2>안전한 이유</h2>
 * 초기 크기를 줄여도 정확성에 영향이 없다. 원본 {@link #feed(ByteBuffer)}가
 * {@code if (buf.capacity() < currentPrelude.getTotalLength())}에서 <b>메시지 크기에 맞춰
 * 버퍼를 재할당</b>하기 때문이다(원본 주석도 "Will grow as needed"라고 명시). 큰 메시지가 오면
 * 그 스트림의 버퍼만 1회 커지고 이후 재사용된다.
 *
 * <h2>업그레이드 시 주의</h2>
 * <b>이 파일은 라이브러리 클래스의 포크다.</b> eventstream 의존성이 올라가면 원본과 대조해
 * 로직 변경분을 반영해야 한다. {@code MessageDecoderShadowingTest}가 (a) 섀도잉이 실제로
 * 걸렸는지 (b) 디코딩이 원본과 동일하게 동작하는지를 검증하므로, 그 테스트가 깨지면
 * 이 파일을 먼저 볼 것.
 */
public final class MessageDecoder {

    /**
     * 초기 버퍼 크기. 원본은 2 MiB 고정이었다.
     *
     * <p>기본 32 KiB — Bedrock 메시지(약 250B)의 100배가 넘는 여유이면서 원본의 1/64다.
     * 그보다 큰 단일 메시지가 오면 위 {@code feed}가 정확한 크기로 재할당하므로 안전하다.
     * {@code -Dsmalltown.eventstream.initial-buffer-bytes=N}으로 재컴파일 없이 조정할 수 있다
     * (부하테스트로 크기를 스윕하기 위한 것).
     */
    private static final int INITIAL_BUFFER_SIZE =
            Integer.getInteger("smalltown.eventstream.initial-buffer-bytes", 32 * 1024);

    private final Consumer<Message> messageConsumer;
    private List<Message> bufferedOutput;
    private ByteBuffer buf;
    private Prelude currentPrelude;

    /**
     * Creates a {@code MessageDecoder} instance that will buffer messages internally as they are decoded. Decoded
     * messages can be obtained by calling {@link #getDecodedMessages()}.
     */
    public MessageDecoder() {
        this.messageConsumer = message -> this.bufferedOutput.add(message);
        this.bufferedOutput = new ArrayList<>();
        this.buf = ByteBuffer.allocate(INITIAL_BUFFER_SIZE);
    }

    /**
     * Creates a {@code MessageDecoder} instance that will publish messages incrementally to the supplied {@code
     * messageConsumer} as they are decoded. The resulting instance does not support the {@link #getDecodedMessages()}
     * operation, and will throw an exception if it is invoked.
     *
     * @param messageConsumer a function that consumes {@link Message} instances
     */
    public MessageDecoder(Consumer<Message> messageConsumer) {
        this(messageConsumer, INITIAL_BUFFER_SIZE);
    }

    /**
     * To be used by tests only.
     */
    MessageDecoder(Consumer<Message> messageConsumer, int initialBufferSize) {
        this.messageConsumer = messageConsumer;
        this.buf = ByteBuffer.allocate(initialBufferSize);
        this.bufferedOutput = null;
    }

    /**
     * Returns {@link Message} instances that have been decoded since this method was last invoked. Note that this
     * method is only supported if this decoder was not configured to use a custom message consumer.
     *
     * @return all messages decoded since the last invocation of this method
     */
    public List<Message> getDecodedMessages() {
        if (bufferedOutput == null) {
            throw new IllegalStateException("");
        }
        List<Message> ret = bufferedOutput;
        bufferedOutput = new ArrayList<>();
        return Collections.unmodifiableList(ret);
    }

    public void feed(byte[] bytes) {
        feed(ByteBuffer.wrap(bytes));
    }

    public void feed(byte[] bytes, int offset, int length) {
        feed(ByteBuffer.wrap(bytes, offset, length));
    }

    /**
     * Feed the contents of the given {@link ByteBuffer} into this decoder. Messages will be incrementally decoded and
     * buffered or published to the message consumer (depending on configuration).
     *
     * @param byteBuffer a {@link ByteBuffer} whose entire contents will be read into the decoder's internal buffer
     * @return this {@code MessageDecoder} instance
     */
    public MessageDecoder feed(ByteBuffer byteBuffer) {
        int bytesToRead = byteBuffer.remaining();
        int bytesConsumed = 0;
        while (bytesConsumed < bytesToRead) {
            ByteBuffer readView = updateReadView();
            if (currentPrelude == null) {
                // Put only 15 bytes into buffer and compute prelude.
                int numBytesToWrite = Math.min(15 - readView.remaining(),
                    bytesToRead - bytesConsumed);

                feedBuf(byteBuffer, numBytesToWrite);

                bytesConsumed += numBytesToWrite;
                readView = updateReadView();

                // Have enough data to decode the prelude
                if (readView.remaining() >= 15) {
                    currentPrelude = Prelude.decode(readView.duplicate());
                    if (buf.capacity() < currentPrelude.getTotalLength()) {
                        // Don't have enough capacity to hold this message, grow the buffer
                        buf = ByteBuffer.allocate(currentPrelude.getTotalLength());
                        buf.put(readView);
                        readView = updateReadView();
                    }
                }
            }
            // We might not have received enough data to decode the prelude so check for null again
            if (currentPrelude != null) {
                // Only write up to what we need to decode the next message
                int numBytesToWrite = Math.min(currentPrelude.getTotalLength() - readView.remaining(),
                    bytesToRead - bytesConsumed);

                feedBuf(byteBuffer, numBytesToWrite);
                bytesConsumed += numBytesToWrite;
                readView = updateReadView();

                // If we have enough data to decode the message do so and reset the buffer for the next message
                if (readView.remaining() >= currentPrelude.getTotalLength()) {
                    messageConsumer.accept(Message.decode(currentPrelude, readView));
                    buf.clear();
                    currentPrelude = null;
                }
            }
        }

        return this;
    }

    private void feedBuf(ByteBuffer byteBuffer, int numBytesToWrite) {
        buf.put((ByteBuffer) byteBuffer.duplicate().limit(byteBuffer.position() + numBytesToWrite));
        byteBuffer.position(byteBuffer.position() + numBytesToWrite);
    }

    private ByteBuffer updateReadView() {
        return (ByteBuffer) buf.duplicate().flip();
    }

    /**
     * To be used by tests only.
     */
    int currentBufferSize() {
        return buf.capacity();
    }
}
