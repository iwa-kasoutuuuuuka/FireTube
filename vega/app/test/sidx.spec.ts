import {parseSidx} from '../src/player/sidx';

/** version 0 の sidx を組み立てる */
function sidx(timescale: number, firstOffset: number, refs: [number, number][]): Uint8Array {
  const size = 32 + refs.length * 12;
  const buf = new Uint8Array(size);
  const v = new DataView(buf.buffer);
  v.setUint32(0, size);
  buf.set([0x73, 0x69, 0x64, 0x78], 4); // 'sidx'
  v.setUint32(12, 1); // reference_ID
  v.setUint32(16, timescale);
  v.setUint32(20, 0); // earliest_presentation_time
  v.setUint32(24, firstOffset);
  v.setUint16(30, refs.length);
  refs.forEach(([bytes, dur], i) => {
    v.setUint32(32 + i * 12, bytes);
    v.setUint32(36 + i * 12, dur);
    v.setUint32(40 + i * 12, 0x90000000);
  });
  return buf;
}

describe('parseSidx', () => {
  it('computes absolute byte ranges and times', () => {
    const box = sidx(1000, 2, [
      [100, 5000],
      [200, 4000],
    ]);
    const segs = parseSidx(box, 740);
    const first = 740 + box.byteLength + 2;
    expect(segs).toEqual([
      {start: first, end: first + 99, time: 0, duration: 5},
      {start: first + 100, end: first + 299, time: 5, duration: 4},
    ]);
  });

  it('throws when no sidx box is present', () => {
    expect(() => parseSidx(new Uint8Array(16), 0)).toThrow();
  });
});
