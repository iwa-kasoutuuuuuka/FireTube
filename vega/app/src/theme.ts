import {Dimensions} from 'react-native';

/** 1920x1080 基準の寸法を実際の画面幅に合わせて拡縮する (端末ごとの論理解像度の違いを吸収) */
const scale = Dimensions.get('window').width / 1920;
export const s = (n: number) => Math.round(n * scale);

export const colors = {
  background: '#0F0F0F',
  surface: '#1F1F1F',
  surfaceFocused: '#3A3A3A',
  accent: '#FF3D00',
  text: '#FFFFFF',
  textDim: '#AAAAAA',
  error: '#FF8A80',
};

export function formatDuration(totalSeconds: number): string {
  const t = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const sec = t % 60;
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
  return `${h > 0 ? `${h}:` : ''}${mm}:${String(sec).padStart(2, '0')}`;
}
