import React, {useState} from 'react';
import {StyleSheet, TextInput, View} from 'react-native';
import {searchVideos} from '../api/innertube';
import {VideoItem} from '../api/types';
import {VideoGrid} from '../components/VideoGrid';
import {colors, s} from '../theme';

interface Props {
  onSelect: (item: VideoItem) => void;
}

export const SearchScreen = ({onSelect}: Props) => {
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<VideoItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [searched, setSearched] = useState(false);
  const [focused, setFocused] = useState(false);

  const submit = async () => {
    const q = query.trim();
    if (!q) {
      return;
    }
    setLoading(true);
    setError(undefined);
    setItems([]);
    setSearched(true);
    try {
      setItems(await searchVideos(q));
    } catch (e) {
      setError(`検索に失敗しました: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <TextInput
          style={[styles.input, focused && styles.inputFocused]}
          value={query}
          onChangeText={setQuery}
          onSubmitEditing={submit}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          placeholder="検索キーワードを入力して決定"
          placeholderTextColor={colors.textDim}
          returnKeyType="search"
          autoFocus
          hasTVPreferredFocus
        />
      </View>
      <VideoGrid
        items={items}
        loading={loading}
        error={error}
        emptyText={searched ? '見つかりませんでした' : ' '}
        onSelect={onSelect}
        onRetry={submit}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: colors.background},
  header: {paddingHorizontal: s(60), paddingTop: s(40), paddingBottom: s(16)},
  input: {
    color: colors.text,
    fontSize: s(32),
    paddingHorizontal: s(28),
    paddingVertical: s(16),
    borderRadius: s(12),
    borderWidth: s(4),
    borderColor: 'transparent',
    backgroundColor: colors.surface,
  },
  inputFocused: {borderColor: colors.text},
});
