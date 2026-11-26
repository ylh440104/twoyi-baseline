/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records which guest HAL modules reach the host bridge. The legacy ROM's
 * HAL modules connect to abstract sockets named by {@link GuestEndpoints};
 * the host cannot serve their payload yet, so this class only keeps honest
 * evidence of who connected and how the connection ended.
 */
public final class HalConnectionRegistry {
    public enum Outcome { PENDING, UNSUPPORTED, PEER_CLOSED, FAILED }

    private static final int MAX_RECORDS = 16;

    private final Map<String, Outcome> connections = new LinkedHashMap<>();

    /** Returns false when the same module is already being tracked. */
    public synchronized boolean opened(String source) {
        String key = requireName(source);
        if (connections.containsKey(key)) return false;
        if (connections.size() >= MAX_RECORDS) {
            String oldest = connections.keySet().iterator().next();
            connections.remove(oldest);
        }
        connections.put(key, Outcome.PENDING);
        return true;
    }

    public synchronized void finished(String source, Outcome outcome) {
        String key = requireName(source);
        if (outcome == null || outcome == Outcome.PENDING) {
            throw new IllegalArgumentException("A completed HAL connection needs a final outcome");
        }
        if (connections.containsKey(key)) connections.put(key, outcome);
    }

    public synchronized Outcome outcomeOf(String source) {
        return connections.get(requireName(source));
    }

    public synchronized List<String> pending() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Outcome> entry : connections.entrySet()) {
            if (entry.getValue() == Outcome.PENDING) names.add(entry.getKey());
        }
        return Collections.unmodifiableList(names);
    }

    public synchronized int size() { return connections.size(); }

    public synchronized void clear() { connections.clear(); }

    private static String requireName(String source) {
        if (source == null || source.trim().isEmpty() || source.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("HAL source name is required");
        }
        return source;
    }
}
