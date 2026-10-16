import React from 'react';
import {ActivityIndicator, FlatList, StyleSheet, Text, View} from 'react-native';
import {VideoItem} from '../api/types';
import {colors, s} from '../theme';
import {FocusButton} from './FocusButton';
import {VideoCard} from './VideoCard';

interface Props {
  items: VideoItem[];
  loading: boolean;
  error?: string;
  emptyText?: string;
  onSelect: (item: VideoItem) => void;
  onRetry?: () => void;
  focusFirst?: boolean;
}

/** 4 列の動画一覧。読み込み中・エラー・0 件の表示も受け持つ */
export const VideoGrid = ({
  items,
  loading,
  error,
  emptyText,
  onSelect,
  onRetry,
  focusFirst,
}: Props) => {
  if (loading && items.length === 0) {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color={colors.accent} />
      </View>
    );
  }
  if (error && items.length === 0) {
    return (
      <View style={styles.center}>
        <Text style={styles.error}>{error}</Text>
        {onRetry && <FocusButton label="再読み込み" onPress={onRetry} hasTVPreferredFocus />}
      </View>
    );
  }
  if (items.length === 0) {
    return (
      <View style={styles.center}>
        <Text style={styles.message}>{emptyText ?? '動画がありません'}</Text>
      </View>
    );
  }
  return (
    <FlatList
      data={items}
      keyExtractor={(v) => v.id}
      numColumns={4}
      contentContainerStyle={styles.list}
      initialNumToRender={8}
      windowSize={5}
      renderItem={({item, index}) => (
        <VideoCard item={item} onPress={onSelect} hasTVPreferredFocus={focusFirst && index === 0} />
      )}
    />
  );
};

const styles = StyleSheet.create({
  center: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  list: {paddingHorizontal: s(48), paddingBottom: s(48)},
  message: {color: colors.textDim, fontSize: s(32)},
  error: {color: colors.error, fontSize: s(32), marginBottom: s(24), textAlign: 'center'},
});
