package com.huawei.ascend.a2a.memory.experience;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process {@link ExperienceStore} for offline eval and tests. Stores
 * (signature, lesson) records per tenant and, on recall, ranks every stored
 * record by {@link CollaborationSignature#similarity} to the query signature,
 * returning the top-K lessons from the most similar collaborations. Tenant
 * isolation is enforced by the per-tenant partition key.
 */
public final class InMemoryExperienceStore implements ExperienceStore {

    private record Record(CollaborationSignature signature, Lesson lesson) {
    }

    private final ConcurrentMap<String, CopyOnWriteArrayList<Record>> byTenant = new ConcurrentHashMap<>();

    @Override
    public void record(String tenantId, CollaborationSignature signature, List<Lesson> lessons) {
        if (lessons == null || lessons.isEmpty()) {
            return;
        }
        CopyOnWriteArrayList<Record> records = byTenant.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>());
        for (Lesson lesson : lessons) {
            if (lesson != null && lesson.text() != null && !lesson.text().isBlank()) {
                records.add(new Record(signature, lesson));
            }
        }
    }

    @Override
    public List<Lesson> recall(String tenantId, CollaborationSignature signature, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        CopyOnWriteArrayList<Record> records = byTenant.get(tenantId);
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<Record> ranked = new ArrayList<>(records);
        ranked.sort(Comparator.comparingDouble(
                (Record r) -> r.signature().similarity(signature)).reversed());
        List<Lesson> out = new ArrayList<>();
        for (Record r : ranked) {
            if (r.signature().similarity(signature) <= 0.0) {
                break; // ranked desc — nothing relevant left
            }
            out.add(r.lesson());
            if (out.size() >= topK) {
                break;
            }
        }
        return out;
    }
}
