import React, {useEffect, useState} from 'react';
import {ScrollView, StyleSheet, Text, TextInput, View} from 'react-native';
import {getSuggestions} from '../api/extras';
import {searchVideos} from '../api/innertube';
import {VideoItem} from '../api/types';
import {FocusButton} from '../components/FocusButton';
import {VideoGrid} from '../components/VideoGrid';
import {addSearchHistory, searchHistory, useStore} from '../store/library';
import {colors, s} from '../theme';

interface Props {
  onSelect: (item: VideoItem) => void;
}

/** 入力が止まってから候補を取りに行くまでの時間 */
const SUGGEST_DELAY_MS = 300;

/**
 * 検索。リモコンでの文字入力は時間がかかるため、最近の検索と入力中の候補をボタンで出し、
 * 選ぶだけで検索できるようにする
 */
export const SearchScreen = ({onSelect}: Props) => {
  const recent = useStore(searchHistory);
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<VideoItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [searched, setSearched] = useState<string>();
  const [focused, setFocused] = useState(false);
  const [suggestions, setSuggestions] = useState<string[]>([]);

  // 検索済みの語のままなら候補は出さない
  useEffect(() => {
    const q = query.trim();
    if (!q || q === searched) {
      setSuggestions([]);
      return;
    }
    let cancelled = false;
    const timer = setTimeout(() => {
      getSuggestions(q).then((list) => !cancelled && setSuggestions(list));
    }, SUGGEST_DELAY_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query, searched]);

  const search = async (text: string) => {
    const q = text.trim();
    if (!q) {
      return;
    }
    setQuery(q);
    setSuggestions([]);
    addSearchHistory(q);
    setLoading(true);
    setError(undefined);
    setItems([]);
    setSearched(q);
    try {
      setItems(await searchVideos(q));
    } catch (e) {
      setError(`検索に失敗しました: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  const chips = suggestions.length > 0 ? suggestions : searched ? [] : recent;
  const chipsTitle = suggestions.length > 0 ? '候補' : '最近の検索';

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <TextInput
          style={[styles.input, focused && styles.inputFocused]}
          value={query}
          onChangeText={setQuery}
          onSubmitEditing={() => search(query)}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          placeholder="検索キーワードを入力して決定"
          placeholderTextColor={colors.textDim}
          returnKeyType="search"
          autoFocus
          hasTVPreferredFocus
        />
        {chips.length > 0 && (
          <View style={styles.chipsRow}>
            <Text style={styles.chipsTitle}>{chipsTitle}</Text>
            <ScrollView horizontal contentContainerStyle={styles.chips}>
              {chips.map((c) => (
                <FocusButton key={c} label={c} onPress={() => search(c)} />
              ))}
            </ScrollView>
          </View>
        )}
      </View>
      <VideoGrid
        items={items}
        loading={loading}
        error={error}
        emptyText={searched ? '見つかりませんでした' : ' '}
        onSelect={onSelect}
        onRetry={() => search(query)}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: colors.background},
  header: {paddingHorizontal: s(60), paddingTop: s(40), paddingBottom: s(8)},
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
  chipsRow: {flexDirection: 'row', alignItems: 'center', marginTop: s(16)},
  chipsTitle: {color: colors.textDim, fontSize: s(24), marginRight: s(16)},
  chips: {alignItems: 'center'},
});
