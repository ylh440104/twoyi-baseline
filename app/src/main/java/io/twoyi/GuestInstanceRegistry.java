/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-side ownership of guest slots. The newest build keeps four independent
 * guest processes; this registry decides which instance owns which slot, which
 * one is active, and how far it has got. It stores no guest data and starts no
 * process — it only prevents two instances from being handed one slot.
 *
 * The state file is small and rewritten atomically so a crash leaves either the
 * previous registry or the new one, never a half-written list.
 */
public final class GuestInstanceRegistry {
    public enum State { RESERVED, READY, RUNNING }

    public static final int MAX_INSTANCES = GuestSlot.SLOT_COUNT;
    private static final String HEADER = "twoyi-instances-v1";
    private static final int MAX_ID_LENGTH = 64;

    private final Path stateFile;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private String activeId;

    private GuestInstanceRegistry(Path stateFile) {
        this.stateFile = stateFile;
    }

    private static final class Entry {
        final String id;
        final int slot;
        State state = State.RESERVED;

        Entry(String id, int slot) {
            this.id = id;
            this.slot = slot;
        }
    }

    public static GuestInstanceRegistry open(Path stateFile) {
        if (stateFile == null || !stateFile.isAbsolute()) {
            throw new IllegalArgumentException("Registry path must be absolute");
        }
        if (stateFile.getParent() == null) {
            throw new IllegalArgumentException("Registry file needs a parent directory");
        }
        return new GuestInstanceRegistry(stateFile);
    }

    /** The registry is shared by every slot, so it lives in the app data root. */
    public static Path defaultPath(Path dataDir) {
        if (dataDir == null || !dataDir.isAbsolute()) {
            throw new IllegalArgumentException("Data directory must be absolute");
        }
        return dataDir.resolve("instances.tsv");
    }

    /** Read the registry, or return an empty one when the file does not exist. */
    public static GuestInstanceRegistry load(Path stateFile) throws IOException {
        GuestInstanceRegistry registry = open(stateFile);
        if (!Files.exists(stateFile)) return registry;
        List<String> lines = Files.readAllLines(stateFile, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.get(0))) {
            throw new IOException("Not a guest instance registry: " + stateFile);
        }
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("active=")) {
                String id = requireId(line.substring("active=".length()));
                registry.activeId = id;
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length != 3) {
                throw new IOException("Malformed registry line " + (i + 1) + ": " + line);
            }
            String id = requireId(parts[0]);
            int slot = parseSlot(parts[1], i + 1);
            State state = parseState(parts[2], i + 1);
            if (registry.entries.containsKey(id)) {
                throw new IOException("Duplicate instance in registry: " + id);
            }
            for (Entry existing : registry.entries.values()) {
                if (existing.slot == slot) {
                    throw new IOException("Slot " + slot + " is claimed twice");
                }
            }
            Entry entry = new Entry(id, slot);
            entry.state = state;
            registry.entries.put(id, entry);
        }
        if (registry.activeId != null && !registry.entries.containsKey(registry.activeId)) {
            throw new IOException("Active instance is missing: " + registry.activeId);
        }
        return registry;
    }

    public void save() throws IOException {
        Path parent = stateFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        if (activeId != null) lines.add("active=" + activeId);
        for (Entry entry : entries.values()) {
            lines.add(entry.id + "\t" + entry.slot + "\t" + entry.state.name());
        }
        Path tmp = Files.createTempFile(parent, ".instances-", ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** Reserve the lowest free slot for a new instance and return it. */
    public synchronized int allocate(String instanceId) {
        String id = requireId(instanceId);
        if (entries.containsKey(id)) {
            throw new IllegalArgumentException("Instance already allocated: " + id);
        }
        boolean[] taken = new boolean[MAX_INSTANCES];
        for (Entry entry : entries.values()) taken[entry.slot] = true;
        for (int slot = 0; slot < MAX_INSTANCES; slot++) {
            if (taken[slot]) continue;
            entries.put(id, new Entry(id, slot));
            return slot;
        }
        throw new IllegalStateException("No free guest slot for instance " + id);
    }

    public synchronized void release(String instanceId) {
        String id = requireId(instanceId);
        if (entries.remove(id) == null) return;
        if (id.equals(activeId)) activeId = null;
    }

    public synchronized void setState(String instanceId, State state) {
        Entry entry = requireEntry(instanceId);
        if (state == null) throw new IllegalArgumentException("Instance state is required");
        entry.state = state;
    }

    /** Mark one instance active; a released instance can never be activated. */
    public synchronized void activate(String instanceId) {
        activeId = requireEntry(instanceId).id;
    }

    public synchronized String active() {
        return activeId;
    }

    public synchronized int slotOf(String instanceId) {
        return requireEntry(instanceId).slot;
    }

    public synchronized State stateOf(String instanceId) {
        return requireEntry(instanceId).state;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized List<String> instances() {
        return new ArrayList<>(entries.keySet());
    }

    private Entry requireEntry(String instanceId) {
        String id = requireId(instanceId);
        Entry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown instance: " + id);
        return entry;
    }

    private static String requireId(String instanceId) {
        if (instanceId == null) throw new IllegalArgumentException("Instance id is required");
        String id = instanceId.trim();
        if (id.isEmpty()) throw new IllegalArgumentException("Instance id is blank");
        if (id.length() > MAX_ID_LENGTH) throw new IllegalArgumentException("Instance id is too long");
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!ok) throw new IllegalArgumentException("Instance id has an unsupported character: " + c);
        }
        return id;
    }

    private static int parseSlot(String value, int line) throws IOException {
        int slot;
        try {
            slot = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Malformed slot on line " + line + ": " + value, e);
        }
        if (slot < 0 || slot >= MAX_INSTANCES) {
            throw new IOException("Slot out of range on line " + line + ": " + slot);
        }
        return slot;
    }

    private static State parseState(String value, int line) throws IOException {
        try {
            return State.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown state on line " + line + ": " + value, e);
        }
    }
}