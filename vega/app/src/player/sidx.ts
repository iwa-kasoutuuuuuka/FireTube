/** fMP4 の sidx (Segment Index) ボックスから、各セグメントのバイト範囲と時刻を得る */
export interface Segment {
  /** ファイル先頭からのバイト位置 (両端を含む) */
  start: number;
  end: number;
  /** 秒 */
  time: number;
  duration: number;
}

/**
 * @param buf indexRange (sidx ボックス) のバイト列
 * @param boxOffset buf の先頭がファイル内のどの位置か (indexRange.start)
 */
export function parseSidx(buf: Uint8Array, boxOffset: number): Segment[] {
  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  let pos = 0;
  // 先頭に別ボックスがあっても sidx まで進める
  while (pos + 8 <= buf.byteLength) {
    const size = view.getUint32(pos);
    const type = String.fromCharCode(
      buf[pos + 4],
      buf[pos + 5],
      buf[pos + 6],
      buf[pos + 7],
    );
    if (type === 'sidx') {
      break;
    }
    if (size < 8) {
      throw new Error('invalid box');
    }
    pos += size;
  }
  if (pos + 8 > buf.byteLength) {
    throw new Error('sidx not found');
  }

  const boxStart = pos;
  const boxSize = view.getUint32(pos);
  const version = buf[pos + 8];
  pos += 12; // size, type, version+flags
  pos += 4; // reference_ID
  const timescale = view.getUint32(pos);
  pos += 4;
  let earliest: number;
  let firstOffset: number;
  if (version === 0) {
    earliest = view.getUint32(pos);
    firstOffset = view.getUint32(pos + 4);
    pos += 8;
  } else {
    earliest = view.getUint32(pos) * 2 ** 32 + view.getUint32(pos + 4);
    firstOffset = view.getUint32(pos + 8) * 2 ** 32 + view.getUint32(pos + 12);
    pos += 16;
  }
  pos += 2; // reserved
  const count = view.getUint16(pos);
  pos += 2;

  const segments: Segment[] = [];
  let offset = boxOffset + boxStart + boxSize + firstOffset;
  let time = earliest;
  for (let i = 0; i < count; i++) {
    const ref = view.getUint32(pos);
    const dur = view.getUint32(pos + 4);
    pos += 12;
    // 先頭 1 bit は reference_type (0 = メディア)。残り 31 bit がサイズ
    // eslint-disable-next-line no-bitwise
    const size = ref & 0x7fffffff;
    segments.push({
      start: offset,
      end: offset + size - 1,
      time: time / timescale,
      duration: dur / timescale,
    });
    offset += size;
    time += dur;
  }
  return segments;
}
