/*
 *  Copyright 2020 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  Modified for Incari (tools/teavm-patches/README.md): an empty map made from entries (Map.of()
 *  through NEtriesMap, Map.copyOf of an empty map) took each key's hash modulo zero in get(), which
 *  WebAssembly traps on ("remainder by zero"); get() and its entry set's contains() now answer
 *  first, as containsKey() already did.
 *
 *  Modified for Incari: the sets and maps of more than two elements (Set.of, Set.copyOf, Map.of,
 *  Map.copyOf) weren't hashed for lookups. Their tables had a slot per element and no free slot to
 *  end a search, and Set.copyOf's packed its elements together, so contains(), containsKey() and
 *  get() compared every element whenever the one asked for was missing. The game's tag checks
 *  (Holder.is(TagKey), against a Set.copyOf of the holder's tags) are mostly misses. Elements now
 *  stay packed in insertion order for iteration, and a table of their indices, at least twice as
 *  large, is probed from the hash until a free slot (HashSlots).
 */
package org.teavm.classlib.java.util;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

public final class TTemplateCollections {

    private TTemplateCollections() {
    }

    public static class ImmutableArrayList<T> extends AbstractImmutableList<T> implements RandomAccess {
        private final T[] list;

        @SafeVarargs
        public ImmutableArrayList(T... list) {
            this.list = list;
        }

        @SuppressWarnings("unchecked")
        ImmutableArrayList(TCollection<? extends T> collection) {
            T[] list = (T[]) new Object[collection.size()];

            TIterator<? extends T> iter = collection.iterator();
            int index = 0;
            while (iter.hasNext()) {
                T element = iter.next();
                if (element == null) {
                    throw new NullPointerException();
                }
                list[index++] = element;
            }

            this.list = list;
        }

        @Override
        public T get(int index) {
            return list[index];
        }

        @Override
        public int size() {
            return list.length;
        }
    }

    static class SingleElementList<T> extends AbstractImmutableList<T> implements RandomAccess {
        private T value;

        SingleElementList(T value) {
            this.value = value;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public T get(int index) {
            if (index == 0) {
                return value;
            }
            throw new IndexOutOfBoundsException();
        }

        @Override
        public void sort(TComparator<? super T> c) {
        }
    }

    static class TwoElementsList<T> extends AbstractImmutableList<T> implements RandomAccess {
        private T first;
        private T second;

        TwoElementsList(T first, T second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public int size() {
            return 2;
        }

        @Override
        public T get(int index) {
            if (index == 0) {
                return first;
            } else if (index == 1) {
                return second;
            }
            throw new IndexOutOfBoundsException();
        }
    }

    static class SingleElementSet<T> extends AbstractImmutableSet<T> {
        private T element;

        SingleElementSet(T element) {
            this.element = element;
        }

        @Override
        public TIterator<T> iterator() {
            return new TIterator<>() {
                private boolean more = true;

                @Override
                public boolean hasNext() {
                    return more;
                }

                @Override
                public T next() {
                    if (!more) {
                        throw new NoSuchElementException();
                    }
                    more = false;
                    return element;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public boolean contains(Object o) {
            return Objects.equals(o, element);
        }
    }

    static class TwoElementsSet<T> extends AbstractImmutableSet<T> {
        private T first;
        private T second;

        TwoElementsSet(T first, T second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public boolean contains(Object o) {
            return Objects.equals(o, first) || Objects.equals(o, second);
        }

        @Override
        public TIterator<T> iterator() {
            return new TIterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < 2;
                }

                @Override
                public T next() {
                    switch (index++) {
                        case 0:
                            return first;
                        case 1:
                            return second;
                        default:
                            throw new NoSuchElementException();
                    }
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public int size() {
            return 2;
        }
    }

    static class NElementSet<T> extends AbstractImmutableSet<T> {
        private T[] data;
        private int[] slots;

        /**
         * Throws an exception on duplicate elements.
         */
        @SafeVarargs
        NElementSet(T... data) {
            T[] elements = data.clone();
            int[] table = HashSlots.create(elements.length);
            for (int i = 0; i < elements.length; i++) {
                T element = Objects.requireNonNull(elements[i]);
                if (HashSlots.find(table, elements, element) >= 0) {
                    throw new IllegalArgumentException();
                }
                HashSlots.add(table, element, i);
            }
            this.data = elements;
            this.slots = table;
        }

        /**
         * Duplicate elements will be ignored (does NOT throw an exception).
         */
        @SuppressWarnings("unchecked")
        NElementSet(TCollection<T> collection) {
            T[] source = (T[]) collection.toArray();
            T[] elements = (T[]) new Object[source.length];
            int[] table = HashSlots.create(source.length);
            int count = 0;
            for (T element : source) {
                Objects.requireNonNull(element);
                if (HashSlots.find(table, elements, element) >= 0) {
                    continue;
                }
                elements[count] = element;
                HashSlots.add(table, element, count);
                count++;
            }
            this.data = count == elements.length ? elements : Arrays.copyOf(elements, count);
            this.slots = table;
        }

        @Override
        public TIterator<T> iterator() {
            return new TIterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < data.length;
                }

                @Override
                public T next() {
                    if (index >= data.length) {
                        throw new NoSuchElementException();
                    }
                    return data[index++];
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public boolean contains(Object o) {
            if (data.length == 0 || o == null) {
                return false;
            }
            return HashSlots.find(slots, data, o) >= 0;
        }
    }

    static class SingleEntryMap<K, V> extends AbstractImmutableMap<K, V> {
        private Entry<K, V> entry;
        private AbstractImmutableSet<Entry<K, V>> entrySet;
        private AbstractImmutableSet<K> keySet;
        private AbstractImmutableList<V> values;

        SingleEntryMap(K key, V value) {
            this.entry = new ImmutableEntry<>(Objects.requireNonNull(key), Objects.requireNonNull(value));
        }

        @Override
        public TSet<Entry<K, V>> entrySet() {
            if (entrySet == null) {
                entrySet = new SingleElementSet<>(entry);
            }
            return entrySet;
        }

        @Override
        public V get(Object key) {
            return entry.getKey().equals(key) ? entry.getValue() : null;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean containsValue(Object value) {
            return entry.getValue().equals(value);
        }

        @Override
        public boolean containsKey(Object key) {
            return entry.getKey().equals(key);
        }

        @Override
        public TSet<K> keySet() {
            if (keySet == null) {
                keySet = new SingleElementSet<>(entry.getKey());
            }
            return keySet;
        }

        @Override
        public TCollection<V> values() {
            if (values == null) {
                values = new SingleElementList<>(entry.getValue());
            }
            return values;
        }
    }

    static class TwoEntriesMap<K, V> extends AbstractImmutableMap<K, V> {
        private Entry<K, V> first;
        private Entry<K, V> second;
        private AbstractImmutableSet<Entry<K, V>> entrySet;
        private AbstractImmutableSet<K> keySet;
        private AbstractImmutableList<V> values;

        TwoEntriesMap(K k1, V v1, K k2, V v2) {
            this.first = new ImmutableEntry<>(Objects.requireNonNull(k1), Objects.requireNonNull(v1));
            this.second = new ImmutableEntry<>(Objects.requireNonNull(k2), Objects.requireNonNull(v2));
            if (Objects.equals(k1, k2)) {
                throw new IllegalArgumentException();
            }
        }

        @Override
        public TSet<Entry<K, V>> entrySet() {
            if (entrySet == null) {
                entrySet = new TwoElementsSet<>(first, second);
            }
            return entrySet;
        }

        @Override
        public V get(Object key) {
            return first.getKey().equals(key)
                    ? first.getValue()
                    : second.getKey().equals(key) ? second.getValue() : null;
        }

        @Override
        public int size() {
            return 2;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean containsValue(Object value) {
            return first.getValue().equals(value) || second.getValue().equals(value);
        }

        @Override
        public boolean containsKey(Object key) {
            return first.getKey().equals(key) || second.getKey().equals(key);
        }

        @Override
        public TSet<K> keySet() {
            if (keySet == null) {
                keySet = new TwoElementsSet<>(first.getKey(), second.getKey());
            }
            return keySet;
        }


        @Override
        public TCollection<V> values() {
            if (values == null) {
                values = new TwoElementsList<>(first.getValue(), second.getValue());
            }
            return values;
        }
    }

    static class NEtriesMap<K, V> extends AbstractImmutableMap<K, V> {
        private Entry<K, V>[] data;
        private Object[] keys;
        private int[] slots;
        private AbstractImmutableSet<Entry<K, V>> entrySet;

        @SafeVarargs
        NEtriesMap(Entry<K, V>... data) {
            this.data = toEntryArray(data);
        }

        @SuppressWarnings("unchecked")
        NEtriesMap(TMap<? extends K, ? extends V> map) {
            this.data = toEntryArray(map.entrySet().toArray(new TMap.Entry[0]));
        }

        /** Copies the entries in order and hashes their keys; throws on a duplicate key. */
        @SuppressWarnings("unchecked")
        private Entry<K, V>[] toEntryArray(Entry<K, V>[] entries) {
            Entry<K, V>[] table = new Entry[entries.length];
            Object[] entryKeys = new Object[entries.length];
            int[] entrySlots = HashSlots.create(entries.length);
            for (int i = 0; i < entries.length; i++) {
                Entry<K, V> entry = entries[i];
                Objects.requireNonNull(entry.getKey());
                Objects.requireNonNull(entry.getValue());
                if (HashSlots.find(entrySlots, entryKeys, entry.getKey()) >= 0) {
                    throw new IllegalArgumentException();
                }
                table[i] = new ImmutableEntry<>(entry.getKey(), entry.getValue());
                entryKeys[i] = entry.getKey();
                HashSlots.add(entrySlots, entry.getKey(), i);
            }
            keys = entryKeys;
            slots = entrySlots;
            return table;
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public boolean isEmpty() {
            return data.length == 0;
        }

        @Override
        public boolean containsValue(Object value) {
            if (value == null) {
                return false;
            }
            for (Entry<K, V> entry : data) {
                if (entry.getValue().equals(value)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean containsKey(Object key) {
            if (key == null || data.length == 0) {
                return false;
            }
            return HashSlots.find(slots, keys, key) >= 0;
        }

        @Override
        public V get(Object key) {
            if (key == null || data.length == 0) {
                return null;
            }
            int index = HashSlots.find(slots, keys, key);
            return index >= 0 ? data[index].getValue() : null;
        }

        @Override
        public TSet<Entry<K, V>> entrySet() {
            if (entrySet == null) {
                entrySet = new AbstractImmutableSet<>() {
                    @Override
                    public int size() {
                        return data.length;
                    }

                    @Override
                    public TIterator<Entry<K, V>> iterator() {
                        return new TIterator<>() {
                            int index;

                            @Override
                            public boolean hasNext() {
                                return index < data.length;
                            }

                            @Override
                            public Entry<K, V> next() {
                                if (index == data.length) {
                                    throw new NoSuchElementException();
                                }
                                return data[index++];
                            }

                            @Override
                            public void remove() {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }

                    @Override
                    public boolean contains(Object o) {
                        if (!(o instanceof Entry<?, ?> e)) {
                            return false;
                        }

                        Object key = e.getKey();
                        if (key == null || data.length == 0) {
                            return false;
                        }
                        int index = HashSlots.find(slots, keys, key);
                        if (index >= 0) {
                            return data[index].getValue().equals(e.getValue());
                        }
                        return false;
                    }
                };
            }
            return entrySet;
        }
    }

    /**
     * An open-addressing table of element indices (plus one; zero is a free slot), at least twice
     * as large as the elements, so every probe ends at a free slot.
     */
    static final class HashSlots {
        private HashSlots() {
        }

        static int[] create(int elements) {
            int size = 2;
            while (size < elements * 2) {
                size <<= 1;
            }
            return new int[size];
        }

        private static int start(Object element, int[] table) {
            int hash = element.hashCode();
            return (hash ^ (hash >>> 16)) & (table.length - 1);
        }

        /** The index of the element equal to {@code element}, or -1. */
        static int find(int[] table, Object[] elements, Object element) {
            int mask = table.length - 1;
            for (int slot = start(element, table);; slot = (slot + 1) & mask) {
                int entry = table[slot];
                if (entry == 0) {
                    return -1;
                }
                Object candidate = elements[entry - 1];
                if (candidate == element || candidate.equals(element)) {
                    return entry - 1;
                }
            }
        }

        /** Adds the element at {@code index}, which {@link #find} didn't find. */
        static void add(int[] table, Object element, int index) {
            int mask = table.length - 1;
            int slot = start(element, table);
            while (table[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            table[slot] = index + 1;
        }
    }

    static abstract class AbstractImmutableList<T> extends TAbstractList<T> implements RandomAccess {
        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T remove(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void removeRange(int start, int end) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(TCollection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeIf(Predicate<? super T> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(TCollection<?> c) {
            throw new UnsupportedOperationException();
        }
    }

    static abstract class AbstractImmutableSet<T> extends TAbstractSet<T> {
        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(TCollection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeIf(Predicate<? super T> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(TCollection<?> c) {
            throw new UnsupportedOperationException();
        }
    }

    static abstract class AbstractImmutableMap<K, V> extends TAbstractMap<K, V> {
        @Override
        public V put(K key, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putAll(TMap<? extends K, ? extends V> m) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V remove(Object key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object key, Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean replace(K key, V value, V newValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V replace(K key, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V putIfAbsent(K key, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            throw new UnsupportedOperationException();
        }
    }

    static class ImmutableEntry<K, V> implements TMap.Entry<K, V>, Cloneable {
        K key;
        V value;

        ImmutableEntry(K theKey, V theValue) {
            key = theKey;
            value = theValue;
        }

        @Override
        public Object clone() {
            try {
                return super.clone();
            } catch (CloneNotSupportedException e) {
                return null;
            }
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (object instanceof TMap.Entry<?, ?> entry) {
                return TObjects.equals(key, entry.getKey()) && TObjects.equals(value, entry.getValue());
            }
            return false;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public int hashCode() {
            return TObjects.hashCode(key) ^ TObjects.hashCode(value);
        }

        @Override
        public V setValue(V object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }
}
