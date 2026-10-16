import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, BackHandler, StyleSheet, View} from 'react-native';
import {VideoItem} from './api/types';
import {ChannelScreen} from './screens/ChannelScreen';
import {MainScreen} from './screens/MainScreen';
import {PlayerScreen} from './screens/PlayerScreen';
import {SearchScreen} from './screens/SearchScreen';
import {Channel} from './store/library';
import {hydrateAll} from './store/persisted';
import {colors} from './theme';

type Route =
  | {name: 'main'}
  | {name: 'search'}
  | {name: 'channel'; channel: Channel}
  | {name: 'player'; video: VideoItem};

/**
 * 画面は スタック (トップ → 検索 / チャンネル → 再生) で管理し、リモコンの戻るで 1 階層戻る。
 * トップ・検索は下に残したまま非表示にして、戻ったときに一覧とフォーカスを失わないようにする。
 */
export const App = () => {
  const [ready, setReady] = useState(false);
  const [stack, setStack] = useState<Route[]>([{name: 'main'}]);

  useEffect(() => {
    hydrateAll().finally(() => setReady(true));
  }, []);

  const push = useCallback(
    (route: Route) => setStack((st) => [...st, route]),
    [],
  );
  const pop = useCallback(
    () => setStack((st) => (st.length > 1 ? st.slice(0, -1) : st)),
    [],
  );
  /** 再生画面から別の画面へ移るときは積み増さずに置き換える (再生画面へ戻らない) */
  const replaceTop = useCallback(
    (route: Route) =>
      setStack((st) => {
        const base = st[st.length - 1].name === 'player' ? st.slice(0, -1) : st;
        return [...base, route];
      }),
    [],
  );

  // BackHandler は後から登録したものが先に呼ばれる。画面側 (再生中のメニュー等) が先に処理できるよう、
  // ここは起動時に 1 回だけ登録し、スタックの深さは ref で読む
  const depth = useRef(stack.length);
  depth.current = stack.length;
  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      // トップでは処理せず、既定の動作 (アプリを閉じる) に任せる
      if (depth.current <= 1) {
        return false;
      }
      pop();
      return true;
    });
    return () => sub.remove();
  }, [pop]);

  const openVideo = useCallback(
    (video: VideoItem) => replaceTop({name: 'player', video}),
    [replaceTop],
  );
  const openChannel = useCallback(
    (channel: Channel) => replaceTop({name: 'channel', channel}),
    [replaceTop],
  );

  if (!ready) {
    return (
      <View style={[styles.root, styles.center]}>
        <ActivityIndicator size="large" color={colors.accent} />
      </View>
    );
  }

  const top = stack[stack.length - 1];
  const hasSearch = stack.some((r) => r.name === 'search');

  return (
    <View style={styles.root}>
      <Layer visible={top.name === 'main'}>
        <MainScreen
          onSelect={openVideo}
          onSearch={() => push({name: 'search'})}
          onOpenChannel={openChannel}
        />
      </Layer>
      {hasSearch && (
        <Layer visible={top.name === 'search'}>
          <SearchScreen onSelect={openVideo} />
        </Layer>
      )}
      {top.name === 'channel' && (
        <Layer visible>
          <ChannelScreen
            key={top.channel.id}
            channel={top.channel}
            onSelect={openVideo}
          />
        </Layer>
      )}
      {top.name === 'player' && (
        <Layer visible>
          <PlayerScreen
            key={top.video.id}
            video={top.video}
            onEnded={pop}
            onOpenChannel={openChannel}
          />
        </Layer>
      )}
    </View>
  );
};

/** 非表示の画面はフォーカスも受けないよう display: none にする */
const Layer = ({
  visible,
  children,
}: {
  visible: boolean;
  children: React.ReactNode;
}) => (
  <View style={[StyleSheet.absoluteFill, !visible && styles.hidden]}>
    {children}
  </View>
);

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: colors.background},
  center: {alignItems: 'center', justifyContent: 'center'},
  hidden: {display: 'none'},
});
