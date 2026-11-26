package ai.paai.link.core.util

/**
 * Small thread-safe map. Every operation holds one lock; [keys], [values] and iteration return
 * snapshots, so callers can iterate while other threads modify the map.
 */
class ConcurrentMap<K, V> {
    private val lock = Lock()
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? = lock.withLock { map[key] }
    operator fun set(key: K, value: V) { lock.withLock { map[key] = value } }
    fun put(key: K, value: V): V? = lock.withLock { map.put(key, value) }
    fun putIfAbsent(key: K, value: V): V? = lock.withLock { map[key] ?: run { map[key] = value; null } }
    fun remove(key: K): V? = lock.withLock { map.remove(key) }
    /** Removes [key] only if it currently maps to [value]. */
    fun remove(key: K, value: V): Boolean = lock.withLock { if (map[key] == value) { map.remove(key); true } else false }
    fun containsKey(key: K): Boolean = lock.withLock { map.containsKey(key) }
    fun getOrPut(key: K, create: () -> V): V = lock.withLock { map.getOrPut(key, create) }
    fun clear() = lock.withLock { map.clear() }
    val size: Int get() = lock.withLock { map.size }
    fun isEmpty(): Boolean = size == 0
    val keys: Set<K> get() = lock.withLock { map.keys.toSet() }
    val values: List<V> get() = lock.withLock { map.values.toList() }
    fun snapshot(): Map<K, V> = lock.withLock { LinkedHashMap(map) }
    operator fun iterator(): Iterator<Map.Entry<K, V>> = snapshot().entries.iterator()
}

/** Thread-safe set (see [ConcurrentMap]). */
class ConcurrentSet<T> {
    private val lock = Lock()
    private val set = LinkedHashSet<T>()

    fun add(item: T): Boolean = lock.withLock { set.add(item) }
    fun remove(item: T): Boolean = lock.withLock { set.remove(item) }
    operator fun contains(item: T): Boolean = lock.withLock { item in set }
    fun clear() = lock.withLock { set.clear() }
    fun snapshot(): Set<T> = lock.withLock { set.toSet() }
}

/** Small map with ordered Long keys (the jitter buffer holds at most a few dozen packets). Not thread-safe. */
class SortedLongMap<V> {
    class Entry<V>(val key: Long, val value: V)

    private val keys = ArrayList<Long>()
    private val map = HashMap<Long, V>()

    val size: Int get() = keys.size
    fun isEmpty() = keys.isEmpty()
    fun containsKey(key: Long) = map.containsKey(key)
    operator fun get(key: Long): V? = map[key]

    operator fun set(key: Long, value: V) {
        if (map.put(key, value) == null) {
            var i = keys.size
            while (i > 0 && keys[i - 1] > key) i--
            keys.add(i, key)
        }
    }

    fun remove(key: Long): V? {
        val v = map.remove(key) ?: return null
        keys.remove(key)
        return v
    }

    fun firstKey(): Long = keys.first()

    fun pollFirstEntry(): Entry<V>? {
        if (keys.isEmpty()) return null
        val k = keys.removeAt(0)
        return Entry(k, map.remove(k)!!)
    }

    fun clear() { keys.clear(); map.clear() }
}

/** Remembers the last [capacity] IDs; [add] returns false for an ID already seen. Not thread-safe. */
class DedupeSet(private val capacity: Int) {
    private val set = LinkedHashSet<DeviceId>()

    fun add(id: DeviceId): Boolean {
        if (!set.add(id)) return false
        if (set.size > capacity) set.remove(set.first())
        return true
    }
}
