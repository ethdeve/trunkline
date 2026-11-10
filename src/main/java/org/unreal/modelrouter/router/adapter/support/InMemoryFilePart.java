/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.support;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存物化的文件 part（对齐上游 3.5.3 #637）。
 *
 * <p>入站 multipart 的 {@link FilePart} 内容流绑定在入站 exchange 上，若出站请求
 * 在入站请求生命周期之后才写 body（异步链路），part 内容可能已被释放。
 * 用 {@link #materialize(FilePart)} 在组装请求前把文件内容读入内存，
 * 保留文件名与 headers，出站时作为普通 FilePart 写出。
 * @author Trunkline Team
 */
public final class InMemoryFilePart implements FilePart {

    private final String name;
    private final String filename;
    private final org.springframework.http.HttpHeaders headers;
    private final byte[] data;

    public InMemoryFilePart(final String name, final String filename,
                            final org.springframework.http.HttpHeaders headers, final byte[] data) {
        this.name = name;
        this.filename = filename;
        this.headers = headers;
        this.data = data;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String filename() {
        return filename;
    }

    @Override
    public org.springframework.http.HttpHeaders headers() {
        return headers;
    }

    @Override
    public Flux<DataBuffer> content() {
        // defer：每次订阅给一个新 buffer 对象，内容共享同一数组（一次性写场景安全）
        return Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(data)));
    }

    @Override
    public Mono<Void> transferTo(final Path destination) {
        return Mono.fromCallable(() -> {
            final Path parent = destination.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(destination, data,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return null;
        });
    }

    /**
     * 把入站文件 part 的内容完整读入内存。
     * 每个 DataBuffer 读取后立即释放，防泄漏。
     */
    public static Mono<InMemoryFilePart> materialize(final FilePart part) {
        return part.content()
                .map(db -> {
                    try {
                        byte[] bytes = new byte[db.readableByteCount()];
                        db.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(db);
                    }
                })
                .collectList()
                .map(chunks -> {
                    int total = chunks.stream().mapToInt(c -> c.length).sum();
                    byte[] all = new byte[total];
                    int pos = 0;
                    for (byte[] chunk : chunks) {
                        System.arraycopy(chunk, 0, all, pos, chunk.length);
                        pos += chunk.length;
                    }
                    return new InMemoryFilePart(part.name(), part.filename(), part.headers(), all);
                });
    }

    /**
     * 从 multipart 表单中取出指定名字的文件 part（返回对象本身，未物化）。
     */
    public static FilePart findFilePart(final MultiValueMap<String, org.springframework.http.codec.multipart.Part> parts,
                                        final String name) {
        return parts.getOrDefault(name, List.of()).stream()
                .filter(FilePart.class::isInstance)
                .map(FilePart.class::cast)
                .findFirst()
                .orElse(null);
    }

    /**
     * 批量物化文件 part 列表（返回基类型列表，供 DTO 直接使用）。
     */
    public static Mono<List<FilePart>> materializeAll(final List<? extends FilePart> parts) {
        return Flux.fromIterable(parts)
                .flatMap(InMemoryFilePart::materialize)
                .collectList()
                .map(list -> {
                    final List<FilePart> out = new ArrayList<>(list.size());
                    out.addAll(list);
                    return out;
                });
    }
}
