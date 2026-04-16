package com.ran.cjb_agent.service.os;

import com.ran.cjb_agent.model.domain.OsProfile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OsProfile 缓存（connectionId → 探测结果）
 * 避免每次 Agent 执行都重新探测，提升响应速度
 */
@Component
public class OsProfileCache {

    private final ConcurrentHashMap<String, OsProfile> cache = new ConcurrentHashMap<>();

    public void put(String connectionId, OsProfile profile) {
        cache.put(connectionId, profile);
    }

    public Optional<OsProfile> get(String connectionId) {
        return Optional.ofNullable(cache.get(connectionId));
    }

    public OsProfile getOrDefault(String connectionId) {
        return cache.getOrDefault(connectionId, OsProfile.builder().build());
    }

    public void invalidate(String connectionId) {
        cache.remove(connectionId);
    }

    public boolean contains(String connectionId) {
        return cache.containsKey(connectionId);
    }

    public void clear() {
        cache.clear();
    }
}
