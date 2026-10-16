/**
 * 動作の記録 (端末のログ: `loggingctl log` で "[FireTube]" を検索)。
 * 実機・仮想端末で、どこまで進んだか (一覧の取得・再生経路・エラー) を追うため
 */
export function log(...parts: unknown[]) {
  console.log('[FireTube]', ...parts);
}
