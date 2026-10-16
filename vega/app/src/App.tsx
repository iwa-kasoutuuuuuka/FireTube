import React, {useCallback, useEffect, useState} from 'react';
import {BackHandler, StyleSheet, View} from 'react-native';
import {VideoItem} from './api/types';
import {HomeScreen} from './screens/HomeScreen';
import {PlayerScreen} from './screens/PlayerScreen';
import {SearchScreen} from './screens/SearchScreen';
import {colors} from './theme';

type Route =
  | {name: 'home'}
  | {name: 'search'}
  | {name: 'player'; video: VideoItem};

/**
 * 画面は スタック (ホーム → 検索 → 再生) で管理し、リモコンの戻るで 1 階層戻る。
 * ホーム・検索は下に残したまま非表示にして、戻ったときに一覧とフォーカスを失わないようにする。
 */
export const App = () => {
  const [stack, setStack] = useState<Route[]>([{name: 'home'}]);

  const push = useCallback(
    (route: Route) => setStack((st) => [...st, route]),
    [],
  );
  const pop = useCallback(
    () => setStack((st) => (st.length > 1 ? st.slice(0, -1) : st)),
    [],
  );

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      // ホームでは処理せず、既定の動作 (アプリを閉じる) に任せる
      if (stack.length <= 1) {
        return false;
      }
      pop();
      return true;
    });
    return () => sub.remove();
  }, [stack.length, pop]);

  const openVideo = useCallback(
    (video: VideoItem) =>
      setStack((st) => {
        // 再生画面から別の動画へ移るときは積み増さずに置き換える
        const base = st[st.length - 1].name === 'player' ? st.slice(0, -1) : st;
        return [...base, {name: 'player', video}];
      }),
    [],
  );

  const top = stack[stack.length - 1];
  const hasSearch = stack.some((r) => r.name === 'search');

  return (
    <View style={styles.root}>
      <Layer visible={top.name === 'home'}>
        <HomeScreen
          onSelect={openVideo}
          onSearch={() => push({name: 'search'})}
        />
      </Layer>
      {hasSearch && (
        <Layer visible={top.name === 'search'}>
          <SearchScreen onSelect={openVideo} />
        </Layer>
      )}
      {top.name === 'player' && (
        <Layer visible>
          <PlayerScreen key={top.video.id} video={top.video} onEnded={pop} />
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
  hidden: {display: 'none'},
});
