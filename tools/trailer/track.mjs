// The trailer's music, synthesized like the game's sounds (../sound/engine.mjs): calm, 90 BPM in A minor, laid out
// in sections that follow the edit (SECTIONS, by bar): an intro of pad and plucks, a soft groove under the features,
// a lift under each time-lapse (the second the biggest, with a lead), a breakdown, and a held chord under the end card.
//
//   node tools/trailer/track.mjs build/trailer/track.wav
//
// A bar is 8/3 s.

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { SR, biquad, mulberry32 } from "../sound/engine.mjs";

const BPM = 90, BEAT = 60 / BPM, BAR = BEAT * 4, E8 = BEAT / 2, S16 = BEAT / 4;

// Each bar's section, from the edit (teaser.shot): which instruments play and how hard.
const SECTIONS = [
  { from: 0, to: 2, kind: "intro" }, // the showcase pan
  { from: 2, to: 8, kind: "groove" }, // the features
  { from: 8, to: 12, kind: "lift" }, // the first time-lapse
  { from: 12, to: 15, kind: "groove" }, // notes, the minimap, the library
  { from: 15, to: 20, kind: "peak" }, // the second time-lapse
  { from: 20, to: 21, kind: "breakdown" }, // the finished line
  { from: 21, to: 23, kind: "end" }, // the end card
];
const END_BAR = 21, LENGTH = 23 * BAR + 2.5;
const N = Math.ceil(LENGTH * SR);
const sectionOf = (bar) => SECTIONS.find((s) => bar >= s.from && bar < s.to)?.kind ?? "end";

const mtof = (m) => 440 * Math.pow(2, (m - 69) / 12);
const at = (t) => Math.round(t * SR);

const bus = () => ({ L: new Float32Array(N), R: new Float32Array(N) });
const drums = bus(), music = bus(), verbSend = bus(), delaySend = bus();
const duck = new Float32Array(N).fill(1);

function add(b, i0, buf, gain, pan = 0, verb = 0, delay = 0) {
  const gl = gain * Math.cos(((pan + 1) * Math.PI) / 4) * Math.SQRT2;
  const gr = gain * Math.sin(((pan + 1) * Math.PI) / 4) * Math.SQRT2;
  for (let i = 0; i < buf.length; i++) {
    const j = i0 + i;
    if (j < 0 || j >= N) continue;
    b.L[j] += buf[i] * gl;
    b.R[j] += buf[i] * gr;
    verbSend.L[j] += buf[i] * gl * verb;
    verbSend.R[j] += buf[i] * gr * verb;
    delaySend.L[j] += buf[i] * gl * delay;
    delaySend.R[j] += buf[i] * gr * delay;
  }
}

function noise(n, seed) {
  const r = mulberry32(seed), b = new Float32Array(n);
  for (let i = 0; i < n; i++) b[i] = r() * 2 - 1;
  return b;
}

const saw = (ph) => 2 * (ph - Math.floor(ph + 0.5));

// region Drums: soft, rounded, low in the mix

function kick(t, vel = 1, long = false) {
  const n = at(long ? 1.6 : 0.5), b = new Float32Array(n);
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    ph += (2 * Math.PI * ((long ? 40 : 48) + 70 * Math.exp(-tt / 0.035))) / SR;
    b[i] = Math.sin(ph) * Math.exp(-tt / (long ? 0.7 : 0.24)) * Math.min(1, tt / 0.003);
  }
  add(drums, at(t), b, 0.62 * vel);
  for (let i = 0; i < at(0.6); i++) {
    const j = at(t) + i;
    if (j >= N) break;
    duck[j] = Math.min(duck[j], 1 - 0.3 * vel * Math.exp(-Math.pow(i / SR / 0.16, 1.5)));
  }
}

function rim(t, vel = 1) {
  const n = at(0.12), b = noise(n, at(t) + 11);
  biquad(b, "bandpass", 2300, 2.2);
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    ph += (2 * Math.PI * 820) / SR;
    b[i] = b[i] * 1.6 * Math.exp(-tt / 0.01) + Math.sin(ph) * Math.exp(-tt / 0.012) * 0.5;
  }
  add(drums, at(t), b, 0.2 * vel, 0.1, 0.3);
}

function clap(t, vel = 1) {
  const n = at(0.35), b = noise(n, at(t) + 17);
  biquad(b, "bandpass", 1400, 0.9);
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    let env = 0;
    for (const o of [0, 0.01, 0.02]) if (tt >= o) env = Math.max(env, Math.exp(-(tt - o) / 0.005));
    if (tt >= 0.028) env = Math.max(env, 0.5 * Math.exp(-(tt - 0.028) / 0.1));
    b[i] *= env * 1.8;
  }
  add(drums, at(t), b, 0.22 * vel, -0.05, 0.4);
}

function hat(t, vel = 1, pan = 0.25, open = false) {
  const n = at(open ? 0.22 : 0.05), b = noise(n, at(t) + 3);
  biquad(b, "highpass", 8000, 0.7);
  for (let i = 0; i < n; i++) b[i] *= Math.exp(-i / SR / (open ? 0.07 : 0.014));
  add(drums, at(t), b, (open ? 0.1 : 0.12) * vel, pan, 0.08);
}

/** A soft breath of filtered noise rising into a downbeat. */
function swell(t0, t1, gain = 1) {
  const n = at(t1 - t0), b = noise(n, at(t0) + 99);
  biquad(b, "bandpass", (i) => 500 * Math.pow(3500 / 500, i / n), 0.9);
  for (let i = 0; i < n; i++) b[i] *= Math.pow(i / n, 2) * (1 - Math.pow(i / n, 12));
  add(music, at(t0), b, 0.1 * gain, 0, 0.6);
}

// endregion

// region Instruments

const CHORDS = [
  { root: 45, pad: [57, 60, 64, 71], arp: [69, 72, 76, 79] }, // Am9
  { root: 41, pad: [57, 60, 64, 67], arp: [69, 72, 76, 77] }, // Fmaj9
  { root: 48, pad: [55, 60, 64, 67], arp: [67, 72, 74, 76] }, // Cadd9
  { root: 43, pad: [55, 59, 62, 69], arp: [67, 71, 74, 79] }, // G6/9
];
const SUS = { root: 43, pad: [55, 60, 62, 67], arp: [67, 72, 74, 79] }; // Gsus4, before the end
const END = { root: 48, pad: [55, 59, 62, 64, 71], arp: [72, 76, 79, 83] }; // Cmaj9

function pad(t, dur, notes, bright = 1, gain = 1) {
  const n = at(dur + 2.5);
  for (let k = 0; k < notes.length; k++) {
    for (const det of [-0.08, 0, 0.08]) {
      const f = mtof(notes[k] + det), b = new Float32Array(n);
      let ph = mulberry32(k * 31 + Math.round(det * 100) + at(t))();
      for (let i = 0; i < n; i++) {
        const tt = i / SR;
        ph += f / SR;
        const env = Math.min(1, tt / 0.45) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.7));
        b[i] = (saw(ph) * 0.6 + Math.sin(2 * Math.PI * ph) * 0.4) * env;
      }
      biquad(b, "lowpass", 700 + 900 * bright, 0.6);
      add(music, at(t), b, 0.024 * gain, det === 0 ? 0 : det < 0 ? -0.5 : 0.5, 0.5);
    }
  }
}

function bass(t, dur, midi, vel = 1) {
  const n = at(dur + 0.2), f = mtof(midi), b = new Float32Array(n);
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    ph += f / SR;
    const env = Math.min(1, tt / 0.015) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.06));
    b[i] = (Math.sin(2 * Math.PI * ph) + saw(ph) * 0.25) * env;
  }
  biquad(b, "lowpass", 320, 0.8);
  add(music, at(t), b, 0.3 * vel);
}

function pluck(t, midi, vel = 1, pan = 0) {
  const n = at(0.7), f = mtof(midi), b = new Float32Array(n);
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    ph += f / SR;
    b[i] = (Math.sin(2 * Math.PI * ph) * 0.7 + saw(ph) * 0.3) * Math.exp(-tt / 0.22) * Math.min(1, tt / 0.004);
  }
  biquad(b, "lowpass", (i) => 600 + 2600 * Math.exp(-i / SR / 0.07), 0.8);
  add(music, at(t), b, 0.07 * vel, pan, 0.3, 0.35);
}

/** A soft bell: a sine and its octave and twelfth, the overtones dying first. */
function bell(t, midi, vel = 1, pan = 0) {
  const n = at(2.2), f = mtof(midi), b = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const tt = i / SR, w = 2 * Math.PI * f * tt;
    b[i] = (Math.sin(w) * Math.exp(-tt / 0.9) + 0.35 * Math.sin(2 * w) * Math.exp(-tt / 0.35)
      + 0.15 * Math.sin(3 * w) * Math.exp(-tt / 0.15)) * Math.min(1, tt / 0.003);
  }
  add(music, at(t), b, 0.05 * vel, pan, 0.45, 0.25);
}

/** The peak's lead: a warm square-ish voice with a little vibrato, through the echo. */
function lead(t, dur, midi, vel = 1) {
  const n = at(dur + 0.4), f = mtof(midi), b = new Float32Array(n);
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    ph += (f * (1 + 0.004 * Math.sin(2 * Math.PI * 5 * tt) * Math.min(1, tt / 0.3))) / SR;
    const env = Math.min(1, tt / 0.02) * (tt < dur ? 1 - 0.25 * Math.min(1, tt / 0.6) : Math.exp(-(tt - dur) / 0.12) * 0.75);
    b[i] = (Math.tanh(Math.sin(2 * Math.PI * ph) * 2.2) * 0.7 + saw(ph) * 0.15) * env;
  }
  biquad(b, "lowpass", 2600, 0.7);
  add(music, at(t), b, 0.055 * vel, 0.1, 0.35, 0.3);
}

// endregion

// region The arrangement

const BELLS = [76, 74, 72, 71];
const LEAD = [ // the peak's melody, a bar of quarters and halves each, over the four chords
  [[0, 2, 76], [2, 1, 74], [3, 1, 72]],
  [[0, 2, 72], [2, 2, 69]],
  [[0, 1, 67], [1, 1, 72], [2, 2, 76]],
  [[0, 3, 74], [3, 1, 71]],
];
for (let bar = 0; bar < END_BAR; bar++) {
  const t0 = bar * BAR, kind = sectionOf(bar);
  const ch = bar === END_BAR - 1 ? SUS : CHORDS[bar % 4];
  const intro = kind === "intro", lift = kind === "lift", peak = kind === "peak", down = kind === "breakdown";
  pad(t0, BAR, ch.pad, intro || down ? 0.35 : peak ? 1.1 : 0.8, intro ? 0.85 : peak ? 1.1 : 1);
  // Plucks: eighths, sixteenths through the peak.
  const steps = peak ? 16 : 8, dt = BAR / steps;
  for (let s = 0; s < steps; s++) {
    const order = [0, 2, 1, 3, 2, 0, 3, 1];
    pluck(t0 + s * dt, ch.arp[order[s % 8]] + (peak && s % 4 === 3 ? 12 : 0), (s % 2 ? 0.55 : 0.85) * (intro || down ? 0.65 : 1), s % 2 ? 0.3 : -0.3);
  }
  if (lift || peak || (kind === "groove" && bar % 2 === 1)) bell(t0 + (bar % 2 ? BEAT * 2 : 0), BELLS[bar % 4] + 12, 0.8, bar % 2 ? 0.35 : -0.35);
  if (peak) for (const [b, d, m] of LEAD[bar % 4]) lead(t0 + b * BEAT, d * BEAT - 0.06, m, 1);
  // Into the next section: a swell on the last two beats.
  if (sectionOf(bar + 1) !== kind) swell(t0 + BEAT * 2, t0 + BAR, peak || lift ? 1 : 0.7);
  if (intro || down) continue;
  if (lift || peak) for (let e = 0; e < 8; e++) bass(t0 + e * E8, E8 - 0.04, ch.root - 12 + (e % 4 === 3 ? 12 : 0), e % 2 ? 0.7 : 0.9);
  else bass(t0, BAR - 0.1, ch.root - 12);
  for (let b = 0; b < 4; b++) {
    const tb = t0 + b * BEAT;
    if (lift || peak) kick(tb, b === 0 ? 1 : 0.85);
    else if (b % 2 === 0) kick(tb, b === 0 ? 1 : 0.8);
    if (b % 2 === 1) (peak ? clap : rim)(tb, 0.9);
    hat(tb, 0.5);
    hat(tb + E8, 0.8, -0.2, peak && b % 2 === 1);
    if (lift || peak) {
      hat(tb + S16, 0.35, 0.4);
      hat(tb + 3 * S16, 0.35, 0.4);
    }
  }
}
// The end card: a soft low thump, the chord held and opening up, the bells resolving.
{
  const t0 = END_BAR * BAR;
  kick(t0, 0.7, true);
  pad(t0, BAR * 2, END.pad, 1, 1.2);
  bass(t0, BAR * 1.8, END.root - 12, 0.8);
  [79, 76, 72, 71].forEach((m, k) => bell(t0 + k * BEAT, m + 12, 0.9 - k * 0.15, k % 2 ? 0.3 : -0.3));
  [84, 83, 79].forEach((m, k) => bell(t0 + BAR + k * BEAT * 1.5, m, 0.5 - k * 0.1, k % 2 ? -0.3 : 0.3));
}

// endregion

// region Effects and the master

function reverb(inp, out, spread, room = 0.88, damp = 0.4) {
  const combs = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617].map((d) => d + spread);
  const alls = [556, 441, 341, 225].map((d) => d + spread);
  const acc = new Float32Array(N);
  for (const d of combs) {
    const buf = new Float32Array(d);
    let idx = 0, low = 0;
    for (let i = 0; i < N; i++) {
      const y = buf[idx];
      low = y * (1 - damp) + low * damp;
      buf[idx] = inp[i] * 0.015 + low * room;
      acc[i] += y;
      idx = (idx + 1) % d;
    }
  }
  for (const d of alls) {
    const buf = new Float32Array(d);
    let idx = 0;
    for (let i = 0; i < N; i++) {
      const b = buf[idx], x = acc[i];
      acc[i] = -x + b;
      buf[idx] = x + b * 0.5;
      idx = (idx + 1) % d;
    }
  }
  for (let i = 0; i < N; i++) out[i] += acc[i];
}

/** Echoes a dotted eighth apart, left and right in turn, darker each time. */
function pingpong(send, out) {
  const d = at(E8 * 1.5), fb = 0.38;
  const l = new Float32Array(N), r = new Float32Array(N);
  let lowL = 0, lowR = 0;
  for (let i = 0; i < N; i++) {
    const pl = i >= d ? r[i - d] : 0, pr = i >= d ? l[i - d] : 0;
    lowL += (pl - lowL) * 0.3;
    lowR += (pr - lowR) * 0.3;
    l[i] = send.L[i] + send.R[i] * 0.5 + lowL * fb;
    r[i] = lowR * fb;
    out.L[i] += lowL * 0.55;
    out.R[i] += lowR * 0.55;
  }
}

const wet = bus();
pingpong(delaySend, wet);
reverb(verbSend.L, wet.L, 0);
reverb(verbSend.R, wet.R, 23);

const L = new Float32Array(N), R = new Float32Array(N);
for (let i = 0; i < N; i++) {
  L[i] = drums.L[i] + (music.L[i] + wet.L[i]) * duck[i];
  R[i] = drums.R[i] + (music.R[i] + wet.R[i]) * duck[i];
}
biquad(L, "highpass", 30, 0.7);
biquad(R, "highpass", 30, 0.7);
let peak = 0;
for (let i = 0; i < N; i++) {
  L[i] = Math.tanh(L[i] * 0.9) / 0.9;
  R[i] = Math.tanh(R[i] * 0.9) / 0.9;
  peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]));
}
const norm = 0.85 / peak, fade = at(2.5);
const out = Buffer.alloc(44 + N * 4);
out.write("RIFF", 0);
out.writeUInt32LE(36 + N * 4, 4);
out.write("WAVEfmt ", 8);
out.writeUInt32LE(16, 16);
out.writeUInt16LE(1, 20);
out.writeUInt16LE(2, 22);
out.writeUInt32LE(SR, 24);
out.writeUInt32LE(SR * 4, 28);
out.writeUInt16LE(4, 32);
out.writeUInt16LE(16, 34);
out.write("data", 36);
out.writeUInt32LE(N * 4, 40);
for (let i = 0; i < N; i++) {
  const f = i > N - fade ? (N - i) / fade : 1;
  out.writeInt16LE(Math.round(Math.max(-1, Math.min(1, L[i] * norm * f)) * 32767), 44 + i * 4);
  out.writeInt16LE(Math.round(Math.max(-1, Math.min(1, R[i] * norm * f)) * 32767), 46 + i * 4);
}
const file = process.argv[2] ?? "build/trailer/track.wav";
mkdirSync(dirname(file), { recursive: true });
writeFileSync(file, out);
console.log(`${file}: ${LENGTH.toFixed(2)} s at ${BPM} BPM, end card at ${(END_BAR * BAR).toFixed(2)} s`);

// endregion
