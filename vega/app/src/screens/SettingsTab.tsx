import React, {useState} from 'react';
import {ScrollView, StyleSheet, Text, View} from 'react-native';
import {FocusButton} from '../components/FocusButton';
import {clearHistory, history, useStore, watchLater} from '../store/library';
import {
  QUALITY_OPTIONS,
  settings,
  SubtitleMode,
  updateSettings,
} from '../store/settings';
import {colors, s} from '../theme';
import {APP_VERSION} from '../version';

const SUBTITLE_LABELS: Record<SubtitleMode, string> = {
  off: 'オフ',
  ja: '日本語 (無ければ自動翻訳)',
  default: '動画の既定の言語',
};
const SUBTITLE_ORDER: SubtitleMode[] = ['off', 'ja', 'default'];

const onOff = (v: boolean) => (v ? 'オン' : 'オフ');

/** 次の選択肢へ進める (リモコンでは一覧から選ぶより押すたびに切り替える方が速い) */
function cycle<T>(list: T[], current: T): T {
  return list[(list.indexOf(current) + 1) % list.length];
}

export const SettingsTab = () => {
  const st = useStore(settings);
  const historyCount = useStore(history).length;
  const laterCount = useStore(watchLater).length;
  const [message, setMessage] = useState<string>();

  return (
    <ScrollView contentContainerStyle={styles.root}>
      <Row
        title="最大画質"
        note="DASH 再生の上限。再生が止まりがちなら下げる"
        value={`${st.maxQuality}p`}
        onPress={() =>
          updateSettings({maxQuality: cycle(QUALITY_OPTIONS, st.maxQuality)})
        }
      />
      <Row
        title="字幕"
        note="再生中も下キーのメニューで切り替えられます"
        value={SUBTITLE_LABELS[st.subtitles]}
        onPress={() =>
          updateSettings({subtitles: cycle(SUBTITLE_ORDER, st.subtitles)})
        }
      />
      <Row
        title="SponsorBlock"
        note="スポンサー・イントロ等の区間を自動でスキップ"
        value={onOff(st.sponsorBlock)}
        onPress={() => updateSettings({sponsorBlock: !st.sponsorBlock})}
      />
      <Row
        title="続きから再生"
        note="途中まで見た動画を前回の位置から再生"
        value={onOff(st.resume)}
        onPress={() => updateSettings({resume: !st.resume})}
      />
      <Row
        title="視聴履歴を保存"
        value={onOff(st.saveHistory)}
        onPress={() => updateSettings({saveHistory: !st.saveHistory})}
      />
      <Row
        title="視聴履歴を消去"
        note={`${historyCount} 件 (再生位置も消去)`}
        value="消去"
        onPress={() => {
          clearHistory();
          setMessage('視聴履歴を消去しました');
        }}
      />
      <Row
        title="「後で見る」を空にする"
        note={`${laterCount} 件`}
        value="空にする"
        onPress={() => {
          watchLater.set([]);
          setMessage('「後で見る」を空にしました');
        }}
      />
      {message && <Text style={styles.message}>{message}</Text>}
      <Text style={styles.about}>
        FireTube for Vega OS v{APP_VERSION} (試作版) —
        データはこの端末内にのみ保存されます
      </Text>
    </ScrollView>
  );
};

const Row = ({
  title,
  note,
  value,
  onPress,
}: {
  title: string;
  note?: string;
  value: string;
  onPress: () => void;
}) => (
  <View style={styles.row}>
    <View style={styles.rowText}>
      <Text style={styles.title}>{title}</Text>
      {note && <Text style={styles.note}>{note}</Text>}
    </View>
    <FocusButton label={value} onPress={onPress} />
  </View>
);

const styles = StyleSheet.create({
  root: {paddingHorizontal: s(80), paddingVertical: s(24)},
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: s(14),
    borderBottomWidth: 1,
    borderBottomColor: colors.surface,
  },
  rowText: {flex: 1},
  title: {color: colors.text, fontSize: s(30)},
  note: {color: colors.textDim, fontSize: s(22), marginTop: s(4)},
  message: {color: colors.accent, fontSize: s(26), marginTop: s(24)},
  about: {color: colors.textDim, fontSize: s(22), marginTop: s(40)},
});
