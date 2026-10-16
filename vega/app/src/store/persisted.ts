/**
 * AsyncStorage に JSON で保存する値。メモリに持って同期的に読めるようにし、変更はリスナーへ通知する。
 * 起動時に hydrateAll() で一括して読み込む。
 */
import AsyncStorage from '@react-native-async-storage/async-storage';
import {useSyncExternalStore} from 'react';

export interface Persisted<T> {
  get(): T;
  set(update: T | ((prev: T) => T)): void;
  subscribe(listener: () => void): () => void;
  hydrate(): Promise<void>;
}

const all: Persisted<unknown>[] = [];

export function persisted<T>(
  key: string,
  initial: T,
  normalize: (raw: unknown) => T,
): Persisted<T> {
  let value = initial;
  const listeners = new Set<() => void>();
  let saveTimer: ReturnType<typeof setTimeout> | undefined;

  const save = () => {
    // 再生位置のように頻繁に変わる値もあるため、書き込みはまとめる
    if (saveTimer) {
      clearTimeout(saveTimer);
    }
    saveTimer = setTimeout(() => {
      Promise.resolve()
        .then(() => AsyncStorage.setItem(key, JSON.stringify(value)))
        .catch(() => {});
    }, 500);
  };

  const store: Persisted<T> = {
    get: () => value,
    set(update) {
      const next =
        typeof update === 'function'
          ? (update as (prev: T) => T)(value)
          : update;
      if (next === value) {
        return;
      }
      value = next;
      listeners.forEach((l) => l());
      save();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    async hydrate() {
      try {
        const raw = await AsyncStorage.getItem(key);
        if (raw != null) {
          value = normalize(JSON.parse(raw));
          listeners.forEach((l) => l());
        }
      } catch {
        // 壊れた値は初期値のまま使う
      }
    },
  };
  all.push(store as Persisted<unknown>);
  return store;
}

export function hydrateAll(): Promise<void> {
  return Promise.all(all.map((s) => s.hydrate())).then(() => undefined);
}

export function useStore<T>(store: Persisted<T>): T {
  return useSyncExternalStore(store.subscribe, store.get);
}
