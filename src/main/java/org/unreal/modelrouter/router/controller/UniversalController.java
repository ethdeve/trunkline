/*
 * Copyright 2024 JAiRouter Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.unreal.modelrouter.router.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.unreal.modelrouter.common.dto.ChatDTO;
import org.unreal.modelrouter.common.dto.EmbeddingDTO;
import org.unreal.modelrouter.common.dto.ImageEditDTO;
import org.unreal.modelrouter.common.dto.ImageGenerateDTO;
import org.unreal.modelrouter.common.dto.RerankDTO;
import org.unreal.modelrouter.common.dto.SttDTO;
import org.unreal.modelrouter.common.dto.TtsDTO;
import org.unreal.modelrouter.router.adapter.support.InMemoryFilePart;
import org.unreal.modelrouter.router.handler.ServiceEndpoint;
import org.unreal.modelrouter.router.handler.ServiceRequestHandler;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 统一模型接口控制器.
 *
 * <p>提供兼容OpenAI格式的统一模型服务接口，所有请求处理逻辑委托给 {@link ServiceRequestHandler}。
 *
 * @author JAiRouter Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "统一模型接口", description = "提供兼容OpenAI格式的统一模型服务接口")
public class UniversalController {

    private final ServiceRequestHandler requestHandler;

    public UniversalController(final ServiceRequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    @PostMapping("/chat/completions")
    public Mono<ResponseEntity<?>> chatCompletions(
            @RequestHeader(value = "Authorization", required = false) final String authorization,
            @RequestBody(required = false) final ChatDTO.Request request,
            final ServerWebExchange exchange) {

        // v2.9.9: 原始 DTO 放入 exchange attribute，供 handler 认证后构建响应缓存键
        if (request != null) {
            exchange.getAttributes().put(ServiceRequestHandler.REQUEST_DTO_ATTRIBUTE, request);
        }

        return requestHandler.handleRequest(
            ServiceEndpoint.CHAT,
            request.model(),
            authorization,
            exchange,
            (adapter, auth, httpRequest) -> adapter.chat(request, auth, httpRequest)
        );
    }

    @PostMapping("/embeddings")
    public Mono<ResponseEntity<?>> embeddings(
            @RequestHeader(value = "Authorization", required = false) final String authorization,
            @RequestBody(required = false) final EmbeddingDTO.Request request,
            final ServerWebExchange exchange) {

        if (request == null) {
            throw new ServerWebInputException("Request body is required");
        }

        // v2.9.9: 原始 DTO 放入 exchange attribute，供 handler 认证后构建响应缓存键
        exchange.getAttributes().put(ServiceRequestHandler.REQUEST_DTO_ATTRIBUTE, request);

        return requestHandler.handleRequest(
            ServiceEndpoint.EMBEDDING,
            request.model(),
            authorization,
            exchange,
            (adapter, auth, httpRequest) -> adapter.embedding(request, auth, httpRequest)
        );
    }

    @PostMapping("/rerank")
    public Mono<ResponseEntity<?>> rerank(
            @RequestHeader(value = "Authorization", required = false) final String authorization,
            @RequestBody(required = false) final RerankDTO.Request request,
            final ServerWebExchange exchange) {

        if (request == null) {
            throw new ServerWebInputException("Request body is required");
        }

        // v2.9.9: 原始 DTO 放入 exchange attribute，供 handler 认证后构建响应缓存键
        exchange.getAttributes().put(ServiceRequestHandler.REQUEST_DTO_ATTRIBUTE, request);

        return requestHandler.handleRequest(
            ServiceEndpoint.RERANK,
            request.model(),
            authorization,
            exchange,
            (adapter, auth, httpRequest) -> adapter.rerank(request, auth, httpRequest)
        );
    }

    @PostMapping("/audio/speech")
    public Mono<ResponseEntity<?>> textToSpeech(
            @RequestHeader(value = "Authorization", required = false) final String authorization,
            @RequestBody(required = false) final TtsDTO.Request request,
            final ServerWebExchange exchange) {

        if (request == null) {
            throw new ServerWebInputException("Request body is required");
        }

        return requestHandler.handleRequest(
            ServiceEndpoint.TTS,
            request.model(),
            authorization,
            exchange,
            (adapter, auth, httpRequest) -> adapter.tts(request, auth, httpRequest)
        );
    }

    @PostMapping(value = "/audio/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> speechToText(
            @RequestPart("model") final String model,
            @RequestPart("file") final FilePart file,
            @RequestPart(value = "language", required = false) final String language,
            @RequestPart(value = "prompt", required = false) final String prompt,
            @RequestPart(value = "responseFormat", required = false) final String responseFormat,
            @RequestPart(value = "temperature", required = false) final String temperatureStr,
            @RequestHeader(value = "Authorization", required = false) final String authorization,
            final ServerWebExchange exchange) {

        // 手动转换 temperature 字符串为 Double
        Double temperature = null;
        if (temperatureStr != null && !temperatureStr.isEmpty()) {
            try {
                temperature = Double.parseDouble(temperatureStr);
            } catch (NumberFormatException e) {
                // 忽略无效的温度值
            }
        }

        SttDTO.Request request = new SttDTO.Request(model, file, language, prompt, responseFormat, temperature);

        return requestHandler.handleRequest(
            ServiceEndpoint.STT,
            request.model(),
            authorization,
            exchange,
            (adapter, auth, httpRequest) -> adapter.stt(request, auth, httpRequest)
        );
    }

    @PostMapping("/images/generations")
    public Mono<ResponseEntity<?>> imageGenerate(
            @RequestHeader(value = "Authorization", required = false) final String authorization,
            @RequestBody(required = false) final ImageGenerateDTO.Request request,
            final ServerWebExchange exchange) {

        if (request == null) {
            throw new ServerWebInputException("Request body is required");
        }

        return requestHandler.handleRequest(
            ServiceEndpoint.IMAGE_GEN,
            request.model(),
            authorization,
            exchange,
            (adapter, auth, httpRequest) -> adapter.imageGenerate(request, auth, httpRequest)
        );
    }

    /**
     * 图像编辑（对齐上游 3.5.3 #637）。
     *
     * <p>原实现声明 {@code @RequestBody ImageEditDTO.Request}，但 DTO 携带
     * {@code List<FilePart>}，JSON 永远无法绑定（文件 part 只存在于 multipart 表单）。
     * 现与 STT 一致，仅接受 {@code multipart/form-data}；JSON 调用方将得到 415。
     * 出站前把入站文件 part 物化进内存，避免异步链路中入站内容被提前释放。
     */
    @PostMapping(value = "/images/edits", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> imageEdits(
            @RequestHeader(value = "Authorization", required = false) final String authorization,
            final ServerWebExchange exchange) {

        return exchange.getMultipartData()
                .flatMap(multipart -> {
                    final ImageEditDTO.Request request = buildImageEditRequest(multipart);
                    // 文件 part 出站前先物化进内存
                    return InMemoryFilePart.materializeAll(request.image())
                            .zipWith(request.mask() != null
                                    ? InMemoryFilePart.materialize(request.mask()).map(Optional::of)
                                    : Mono.just(Optional.<FilePart>empty()))
                            .map(tuple -> new ImageEditDTO.Request(
                                    tuple.getT1(), request.prompt(), request.background(),
                                    request.input_fidelity(), tuple.getT2().orElse(null),
                                    request.model(), request.n(), request.output_compression(),
                                    request.output_format(), request.partial_images(),
                                    request.quality(), request.response_format(),
                                    request.size(), request.stream(), request.user()));
                })
                .flatMap(materialized -> requestHandler.handleRequest(
                        ServiceEndpoint.IMAGE_EDIT,
                        materialized.model(),
                        authorization,
                        exchange,
                        (adapter, auth, httpRequest) -> adapter.imageEdit(materialized, auth, httpRequest)
                ));
    }

    /**
     * 从 multipart 表单解析图像编辑请求。
     * 兼容 OpenAI 的 {@code image[]} 与单文件场景的 {@code image} 两种 part 名。
     */
    private ImageEditDTO.Request buildImageEditRequest(final MultiValueMap<String, Part> parts) {
        final List<FilePart> images = new ArrayList<>();
        for (final String name : new String[] {"image", "image[]"}) {
            for (final Part part : parts.getOrDefault(name, List.of())) {
                if (part instanceof FilePart filePart) {
                    images.add(filePart);
                }
            }
        }
        if (images.isEmpty()) {
            throw new ServerWebInputException("multipart part \"image\" (or \"image[]\") is required");
        }

        final String model = textPart(parts, "model");
        if (model == null || model.isBlank()) {
            throw new ServerWebInputException("multipart part \"model\" is required");
        }

        final Integer n = intPart(parts, "n");
        final Integer outputCompression = intPart(parts, "output_compression");
        final Integer partialImages = intPart(parts, "partial_images");
        final Boolean stream = boolPart(parts, "stream");

        return new ImageEditDTO.Request(
                images,
                textPart(parts, "prompt"),
                textPart(parts, "background"),
                textPart(parts, "input_fidelity"),
                InMemoryFilePart.findFilePart(parts, "mask"),
                model,
                n,
                outputCompression,
                textPart(parts, "output_format"),
                partialImages,
                textPart(parts, "quality"),
                textPart(parts, "response_format"),
                textPart(parts, "size"),
                stream,
                textPart(parts, "user")
        );
    }

    private String textPart(final MultiValueMap<String, Part> parts, final String name) {
        final Part part = parts.getFirst(name);
        return part instanceof FormFieldPart field ? field.value() : null;
    }

    private Integer intPart(final MultiValueMap<String, Part> parts, final String name) {
        final String value = textPart(parts, name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            throw new ServerWebInputException("multipart part \"" + name + "\" must be an integer");
        }
    }

    private Boolean boolPart(final MultiValueMap<String, Part> parts, final String name) {
        final String value = textPart(parts, name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }
}
