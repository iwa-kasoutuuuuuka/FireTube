import React from 'react';
import {VideoItem} from '../api/types';
import {VideoGrid} from '../components/VideoGrid';
import {history, useStore, watchLater} from '../store/library';

interface Props {
  kind: 'later' | 'history';
  onSelect: (item: VideoItem) => void;
}

/** 後で見る / 履歴 (どちらも新しい順) */
export const LibraryTab = ({kind, onSelect}: Props) => {
  const later = useStore(watchLater);
  const watched = useStore(history);
  const items = kind === 'later' ? later : watched.map((e) => e.video);
  return (
    <VideoGrid
      items={items}
      loading={false}
      onSelect={onSelect}
      emptyText={
        kind === 'later'
          ? '「後で見る」はまだありません (再生中に下キーのメニューから追加)'
          : '視聴履歴はまだありません'
      }
    />
  );
};
