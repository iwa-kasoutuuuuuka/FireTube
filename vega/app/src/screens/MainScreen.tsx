import React, {useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {VideoItem} from '../api/types';
import {FocusButton} from '../components/FocusButton';
import {Channel} from '../store/library';
import {colors, s} from '../theme';
import {LibraryTab} from './LibraryTab';
import {PopularTab} from './PopularTab';
import {SettingsTab} from './SettingsTab';
import {SubscriptionsTab} from './SubscriptionsTab';

type Tab = 'home' | 'subs' | 'later' | 'history' | 'settings';

const TABS: {id: Tab; label: string}[] = [
  {id: 'home', label: 'ホーム'},
  {id: 'subs', label: '登録チャンネル'},
  {id: 'later', label: '後で見る'},
  {id: 'history', label: '履歴'},
  {id: 'settings', label: '設定'},
];

interface Props {
  onSelect: (item: VideoItem) => void;
  onSearch: () => void;
  onOpenChannel: (channel: Channel) => void;
}

/**
 * タブ付きのトップ画面。タブはフォーカスしただけで切り替える (決定を押す手間を省く)。
 * 一度開いたタブは表示を切り替えるだけで残し、戻ってきたときに読み直さない
 */
export const MainScreen = ({onSelect, onSearch, onOpenChannel}: Props) => {
  const [tab, setTab] = useState<Tab>('home');
  const [opened, setOpened] = useState<Set<Tab>>(new Set(['home']));
  const [reloadKey, setReloadKey] = useState(0);

  const show = (t: Tab) => {
    setTab(t);
    setOpened((o) => (o.has(t) ? o : new Set(o).add(t)));
  };

  const body = (t: Tab) => {
    switch (t) {
      case 'home':
        return <PopularTab onSelect={onSelect} reloadKey={reloadKey} />;
      case 'subs':
        return (
          <SubscriptionsTab
            onSelect={onSelect}
            onOpenChannel={onOpenChannel}
            visible={tab === 'subs'}
            reloadKey={reloadKey}
          />
        );
      case 'later':
      case 'history':
        return <LibraryTab kind={t} onSelect={onSelect} />;
      case 'settings':
        return <SettingsTab />;
    }
  };

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <Text style={styles.logo}>FireTube</Text>
        {TABS.map((t) => (
          <FocusButton
            key={t.id}
            label={t.label}
            selected={tab === t.id}
            onFocus={() => show(t.id)}
            onPress={() => show(t.id)}
          />
        ))}
        <FocusButton label="検索" onPress={onSearch} />
        {(tab === 'home' || tab === 'subs') && (
          <FocusButton
            label="更新"
            onPress={() => setReloadKey((k) => k + 1)}
          />
        )}
      </View>
      {TABS.filter((t) => opened.has(t.id)).map((t) => (
        <View key={t.id} style={[styles.body, tab !== t.id && styles.hidden]}>
          {body(t.id)}
        </View>
      ))}
    </View>
  );
};

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: colors.background},
  header: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    paddingHorizontal: s(60),
    paddingTop: s(32),
    paddingBottom: s(8),
  },
  logo: {
    color: colors.accent,
    fontSize: s(44),
    fontWeight: 'bold',
    marginRight: s(32),
  },
  body: {flex: 1},
  hidden: {display: 'none'},
});
