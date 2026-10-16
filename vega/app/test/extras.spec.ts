jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);

import {formatCount, parseSuggestions} from '../src/api/extras';
import {addSearchHistory, searchHistory} from '../src/store/library';
import {settings} from '../src/store/settings';

describe('extras', () => {
  it('parses suggestion responses defensively', () => {
    expect(parseSuggestions(['ヨル', ['ヨルシカ', 1, '夜に駆ける']])).toEqual([
      'ヨルシカ',
      '夜に駆ける',
    ]);
    expect(parseSuggestions({})).toEqual([]);
  });

  it('formats counts in Japanese units', () => {
    expect(formatCount(3217)).toBe('3,217');
    expect(formatCount(800631)).toBe('80.1万');
    expect(formatCount(1234567)).toBe('123万');
    expect(formatCount(123456789)).toBe('1.2億');
  });

  it('keeps search history newest-first, unique and trimmed', () => {
    searchHistory.set([]);
    addSearchHistory(' a ');
    addSearchHistory('b');
    addSearchHistory('a');
    addSearchHistory('  ');
    expect(searchHistory.get()).toEqual(['a', 'b']);
  });

  it('normalizes autoplay and playback rate settings', async () => {
    const AsyncStorage = require('@react-native-async-storage/async-storage');
    await AsyncStorage.setItem(
      'settings',
      JSON.stringify({autoplay: false, playbackRate: 3}),
    );
    await settings.hydrate();
    expect(settings.get()).toMatchObject({autoplay: false, playbackRate: 1});
  });
});
