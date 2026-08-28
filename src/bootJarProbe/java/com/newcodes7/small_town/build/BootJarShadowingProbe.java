package com.newcodes7.small_town.build;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/** Executed only by the bootJarShadowingTest Gradle task. */
public final class BootJarShadowingProbe {

    private BootJarShadowingProbe() {}

    public static void main(String[] args) throws Exception {
        Class<?> decoderClass = Class.forName("software.amazon.eventstream.MessageDecoder");
        String resource = decoderClass
                .getResource("/software/amazon/eventstream/MessageDecoder.class")
                .toExternalForm();
        if (!resource.contains("BOOT-INF/classes") || resource.contains("BOOT-INF/lib")) {
            throw new AssertionError("MessageDecoder was not loaded from bootJar application classes: " + resource);
        }

        Object decoder = decoderClass.getConstructor().newInstance();
        Field bufferField = decoderClass.getDeclaredField("buf");
        bufferField.setAccessible(true);
        int capacity = ((ByteBuffer) bufferField.get(decoder)).capacity();
        if (capacity != 32 * 1024) {
            throw new AssertionError("Expected the forked 32 KiB buffer but got " + capacity + " bytes");
        }
        System.out.println("bootJar shadowing verified: " + resource + ", initialBuffer=" + capacity);
    }
}
