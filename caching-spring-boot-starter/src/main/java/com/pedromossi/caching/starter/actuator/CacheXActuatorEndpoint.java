package com.pedromossi.caching.starter.actuator;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@Endpoint(id = "cachex")
public class CacheXActuatorEndpoint {

    private final CacheService cacheService;
    private final Optional<CacheProvider> l1CacheProvider;
    private final Optional<CacheProvider> l2CacheProvider;

    public CacheXActuatorEndpoint(
            CacheService cacheService,
            @Qualifier("l1CacheProvider") Optional<CacheProvider> l1CacheProvider,
            @Qualifier("l2CacheProvider") Optional<CacheProvider> l2CacheProvider) {
        this.cacheService = cacheService;
        this.l1CacheProvider = l1CacheProvider;
        this.l2CacheProvider = l2CacheProvider;
    }

    @ReadOperation
    public Map<String, Object> getCacheStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("l1Cache", l1CacheProvider.isPresent() ? "ENABLED" : "DISABLED");
        status.put("l2Cache", l2CacheProvider.isPresent() ? "ENABLED" : "DISABLED");

        Map<String, String> actions = new LinkedHashMap<>();
        actions.put("inspectKey", "GET /actuator/cachex/{key}");
        actions.put("evictKey", "DELETE /actuator/cachex/{key}");
        status.put("actions", actions);

        return status;
    }

    @ReadOperation
    public Map<String, Object> inspectKey(@Selector String key) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("key", key);

        var typeRef = new ParameterizedTypeReference<Object>() {};

        Object l1Value = l1CacheProvider.map(p -> p.get(key, typeRef)).orElse(null);
        if (l1Value != null) {
            details.put("foundIn", "L1");
            details.put("valueType", l1Value.getClass().getName());
        } else {
            Object l2Value = l2CacheProvider.map(p -> p.get(key, typeRef)).orElse(null);
            if (l2Value != null) {
                details.put("foundIn", "L2");
                details.put("valueType", l2Value.getClass().getName());
            } else {
                details.put("foundIn", "NONE");
            }
        }
        return details;
    }

    @DeleteOperation
    public Map<String, String> evictKey(@Selector String key) {
        cacheService.invalidate(key);
        Map<String, String> response = new LinkedHashMap<>();
        response.put("key", key);
        response.put("status", "INVALIDATION_SCHEDULED");
        return response;
    }
}