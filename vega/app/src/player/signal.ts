import {useSyncExternalStore} from 'react';

/**
 * 頻繁に変わる値 (再生位置・字幕) を、表示する小さな部品だけに届けるための入れ物。
 * 再生画面全体の state にすると timeupdate (毎秒 4 回程度) のたびに画面全体を描き直してしまう
 */
export interface Signal<T> {
  get(): T;
  set(value: T): void;
  subscribe(listener: () => void): () => void;
}

export function signal<T>(initial: T): Signal<T> {
  let value = initial;
  const listeners = new Set<() => void>();
  return {
    get: () => value,
    set(next) {
      if (Object.is(next, value)) {
        return;
      }
      value = next;
      listeners.forEach((l) => l());
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export function useSignal<T>(s: Signal<T>): T {
  return useSyncExternalStore(s.subscribe, s.get);
}
