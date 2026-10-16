import React from 'react';
import {FlatList, StyleSheet, Text, View} from 'react-native';
import {VideoItem} from '../api/types';
import {colors, s} from '../theme';
import {FocusButton} from './FocusButton';
import {VideoCard} from './VideoCard';

interface Props {
  title: string;
  items: VideoItem[];
  onSelect: (item: VideoItem) => void;
  /** 再生終了時の「次の動画」: 自動再生までの残り秒数と、取りやめるボタン */
  countdown?: number;
  onCancel?: () => void;
}

/** 再生画面の下に重ねる関連動画の列。左右で選び、決定で再生、戻るで閉じる */
export const RelatedPanel = ({
  title,
  items,
  onSelect,
  countdown,
  onCancel,
}: Props) => (
  <View style={styles.panel}>
    <View style={styles.header}>
      <Text style={styles.title}>
        {title}
        {countdown !== undefined && items[0]
          ? `  —  ${countdown} 秒後に「${items[0].title}」を再生`
          : ''}
      </Text>
      {onCancel && <FocusButton label="自動再生をやめる" onPress={onCancel} />}
    </View>
    {items.length === 0 ? (
      <Text style={styles.empty}>関連動画を読み込んでいます…</Text>
    ) : (
      <FlatList
        horizontal
        data={items}
        keyExtractor={(v) => v.id}
        initialNumToRender={5}
        windowSize={3}
        renderItem={({item, index}) => (
          <VideoCard
            item={item}
            onPress={onSelect}
            hasTVPreferredFocus={index === 0}
          />
        )}
      />
    )}
  </View>
);

const styles = StyleSheet.create({
  panel: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    paddingTop: s(24),
    paddingBottom: s(32),
    paddingHorizontal: s(48),
    backgroundColor: 'rgba(15,15,15,0.92)',
  },
  header: {flexDirection: 'row', alignItems: 'center', marginBottom: s(8)},
  title: {
    color: colors.text,
    fontSize: s(28),
    flex: 1,
    marginLeft: s(12),
  },
  empty: {color: colors.textDim, fontSize: s(26), margin: s(24)},
});
