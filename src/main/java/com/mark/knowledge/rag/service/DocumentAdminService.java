package com.mark.knowledge.rag.service;

import com.mark.knowledge.rag.dto.DocumentDeleteResponse;
import com.mark.knowledge.rag.dto.DocumentListItemResponse;
import com.mark.knowledge.rag.dto.DocumentListResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文档管理服务。
 */
@Service
public class DocumentAdminService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAdminService.class);

    private final String collectionName;
    private final WebClient webClient;

    public DocumentAdminService(
            @Value("${qdrant.host:localhost}") String qdrantHost,
            @Value("${qdrant.http-port:6333}") int qdrantHttpPort,
            @Value("${qdrant.collection-name:knowledge-base}") String collectionName) {
        this.collectionName = collectionName;
        this.webClient = WebClient.builder()
            .baseUrl(String.format("http://%s:%d", qdrantHost, qdrantHttpPort))
            .build();
    }

    public DocumentListResponse listDocuments() {
        List<QdrantPoint> points = scrollAllPoints();
        Map<String, DocumentAggregate> documents = new LinkedHashMap<>();

        for (QdrantPoint point : points) {
            String documentId = extractDocumentId(point.payload());
            if (documentId == null || documentId.isBlank()) {
                continue;
            }

            String filename = extractFilename(point.payload());
            DocumentAggregate aggregate = documents.computeIfAbsent(
                documentId,
                ignored -> new DocumentAggregate(documentId, filename)
            );
            aggregate.increment();
            if ((aggregate.filename == null || aggregate.filename.isBlank())
                    && filename != null && !filename.isBlank()) {
                aggregate.filename = filename;
            }
        }

        List<DocumentListItemResponse> items = documents.values().stream()
            .sorted(Comparator.comparing(DocumentAggregate::filenameOrFallback)
                .thenComparing(DocumentAggregate::documentId))
            .map(item -> new DocumentListItemResponse(
                item.documentId,
                item.filenameOrFallback(),
                item.segmentCount
            ))
            .toList();

        return new DocumentListResponse(items, items.size());
    }

    public DocumentDeleteResponse deleteByDocumentId(String documentId) {
        List<Object> pointIds = scrollAllPoints().stream()
            .filter(point -> documentId.equals(extractDocumentId(point.payload())))
            .map(QdrantPoint::id)
            .filter(Objects::nonNull)
            .toList();

        if (pointIds.isEmpty()) {
            return new DocumentDeleteResponse(documentId, 0, "未找到对应文档");
        }

        Map<String, Object> requestBody = Map.of("points", pointIds);
        webClient.post()
            .uri("/collections/" + collectionName + "/points/delete")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .block();

        log.info("已删除文档: {} ({} 个片段)", documentId, pointIds.size());
        return new DocumentDeleteResponse(documentId, pointIds.size(), "文档删除成功");
    }

    private List<QdrantPoint> scrollAllPoints() {
        List<QdrantPoint> points = new ArrayList<>();
        Object nextOffset = null;

        do {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("limit", 256);
            requestBody.put("with_payload", true);
            requestBody.put("with_vector", false);
            if (nextOffset != null) {
                requestBody.put("offset", nextOffset);
            }

            Map<String, Object> response = webClient.post()
                .uri("/collections/" + collectionName + "/points/scroll")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

            Map<String, Object> result = asMap(response != null ? response.get("result") : null);
            List<Map<String, Object>> pointMaps = asListOfMaps(result.get("points"));
            for (Map<String, Object> pointMap : pointMaps) {
                points.add(new QdrantPoint(pointMap.get("id"), asMap(pointMap.get("payload"))));
            }
            nextOffset = result.get("next_page_offset");
        } while (nextOffset != null);

        return points;
    }

    private String extractDocumentId(Map<String, Object> payload) {
        return firstNonBlank(
            asString(payload.get("documentId")),
            asString(getNestedValue(payload, "metadata", "documentId"))
        );
    }

    private String extractFilename(Map<String, Object> payload) {
        return firstNonBlank(
            asString(payload.get("filename")),
            asString(getNestedValue(payload, "metadata", "filename"))
        );
    }

    private Object getNestedValue(Map<String, Object> source, String parentKey, String childKey) {
        Object nested = source.get(parentKey);
        if (nested instanceof Map<?, ?> nestedMap) {
            return nestedMap.get(childKey);
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private String asString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                converted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return converted;
        }
        return Map.of();
    }

    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rawList) {
            result.add(asMap(item));
        }
        return result;
    }

    private record QdrantPoint(Object id, Map<String, Object> payload) {
    }

    private static final class DocumentAggregate {
        private final String documentId;
        private String filename;
        private int segmentCount;

        private DocumentAggregate(String documentId, String filename) {
            this.documentId = documentId;
            this.filename = filename;
        }

        private void increment() {
            segmentCount++;
        }

        private String documentId() {
            return documentId;
        }

        private String filenameOrFallback() {
            return filename != null && !filename.isBlank() ? filename : "unknown";
        }
    }
}
