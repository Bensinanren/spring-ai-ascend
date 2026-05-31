package com.huawei.ascend.service.sessionmanage.model;

import java.util.Map;
import java.util.Objects;

public record SessionContentPart(
        String type,
        Object value,
        Map<String, Object> metadata) {

    public SessionContentPart {
        Objects.requireNonNull(type, "type");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
