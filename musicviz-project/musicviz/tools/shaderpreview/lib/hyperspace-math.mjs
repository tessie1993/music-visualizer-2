// A JS port of the pure maths behind HYPERSPACE, so the harness can hand the
// shader the same numbers `HyperspaceScene.draw` would.
//
// Ported from (and line-for-line comparable with):
//   render/scene/HyperspaceMath.kt   - bodies, journey, camera, budget, look
//   render/fluid/MeltField.kt        - MeltMath
//   render/fluid/FluidHue.kt         - hsv
//
// KNOWN DIVERGENCE, on purpose: the app uses `kotlin.random.Random.Default`.
// This uses a seeded xorshift so a run is reproducible. Body identities
// (species, axes, hues, sizes) therefore differ from any given device frame -
// the DISTRIBUTIONS are the same, the draw is not. Anything that depends on a
// specific body is not a valid finding from this tool.

export const MAX_BLOOMS = 8;
// Ordinal order, and append-only: SceneParams.hyperSpecies is an index into
// this list (offset by "Mixed") and presets persist the number.
export const SPECIES = ['GASKET', 'TEMPLE', 'JEWEL', 'CORAL', 'BULB', 'SEED'];

export function makeRng(seed = 0x9e3779b9) {
  let s = seed >>> 0 || 1;
  return {
    nextFloat() {
      s ^= s << 13; s >>>= 0;
      s ^= s >>> 17;
      s ^= s << 5; s >>>= 0;
      return s / 4294967296;
    },
    nextBoolean() { return this.nextFloat() < 0.5; },
    nextInt(n) { return Math.min(n - 1, Math.floor(this.nextFloat() * n)); },
  };
}

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

export function smoothstep(edge0, edge1, x) {
  if (edge1 <= edge0) return x < edge0 ? 0 : 1;
  const t = clamp((x - edge0) / (edge1 - edge0), 0, 1);
  return t * t * (3 - 2 * t);
}

export function smoothing(dt, seconds) {
  if (seconds <= 0) return 1;
  return 1 - Math.exp(-dt / seconds);
}

export function lifeEnvelope(age, lifetime, grow, wither) {
  if (lifetime <= 0) return 0;
  if (age <= 0 || age >= lifetime) return 0;
  const half = lifetime * 0.5;
  const g = clamp(grow, 0.01, half);
  const w = clamp(wither, 0.01, half);
  return clamp(smoothstep(0, g, age) * (1 - smoothstep(lifetime - w, lifetime, age)), 0, 1);
}

export function foldFor(species, fold, jitter) {
  const t = clamp(clamp(fold, 0, 1) + jitter * 0.12, 0, 1);
  switch (species) {
    case 'GASKET': return 0.85 + 0.48 * t;
    case 'TEMPLE': return 0.05 + 1.15 * t;
    case 'JEWEL': return -2.4 + 1.5 * t;
    case 'CORAL': return 0.55 + 0.55 * t;
    case 'BULB': return 5 + 6 * t;
    // SEED: where the 3D section cuts the 4D set, not a shape constant.
    default: return 0.08 + 0.44 * t;
  }
}

export function localRadius(species) {
  switch (species) {
    case 'GASKET': return 1.85;
    case 'TEMPLE': return 1.7;
    case 'JEWEL': return 3.2;
    case 'CORAL': return 2.1;
    case 'BULB': return 1.35;
    // SEED: (1 + sqrt(1 + 4|c|)) / 2 = 1.486 at the shader's |c|, rounded up.
    default: return 1.5;
  }
}

export const MAX_LOCAL_RADIUS = Math.max(...SPECIES.map(localRadius));

function axisAngle(axis, angle, out) {
  const len = Math.hypot(axis[0], axis[1], axis[2]);
  if (len < 1e-6) { out.fill(0); out[0] = out[4] = out[8] = 1; return; }
  const x = axis[0] / len, y = axis[1] / len, z = axis[2] / len;
  const c = Math.cos(angle), s = Math.sin(angle), t = 1 - c;
  out[0] = t * x * x + c;     out[1] = t * x * y - s * z; out[2] = t * x * z + s * y;
  out[3] = t * x * y + s * z; out[4] = t * y * y + c;     out[5] = t * y * z - s * x;
  out[6] = t * x * z - s * y; out[7] = t * y * z + s * x; out[8] = t * z * z + c;
}

export function worldToLocalRotation(axisA, angleA, axisB, angleB, out, offset) {
  const a = new Float32Array(9);
  const b = new Float32Array(9);
  axisAngle(axisA, angleA, a);
  axisAngle(axisB, angleB, b);
  for (let c = 0; c < 3; c++) {
    for (let r = 0; r < 3; r++) {
      let sum = 0;
      for (let k = 0; k < 3; k++) sum += a[k * 3 + r] * b[c * 3 + k];
      out[offset + c * 3 + r] = sum;
    }
  }
}

function randomUnitVector(rng, out, offset = 0) {
  const z = rng.nextFloat() * 2 - 1;
  const a = rng.nextFloat() * 2 * Math.PI;
  const r = Math.sqrt(Math.max(0, 1 - z * z));
  out[offset] = r * Math.cos(a);
  out[offset + 1] = r * Math.sin(a);
  out[offset + 2] = z;
}

function randomPlane(rng, u, v) {
  randomUnitVector(rng, u);
  randomUnitVector(rng, v);
  const d = u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
  v[0] -= d * u[0]; v[1] -= d * u[1]; v[2] -= d * u[2];
  let len = Math.hypot(v[0], v[1], v[2]);
  if (len < 1e-4) { v[0] = -u[1]; v[1] = u[0]; v[2] = 0; len = Math.hypot(v[0], v[1], v[2]) || 1; }
  v[0] /= len; v[1] /= len; v[2] /= len;
}

export class Bloom {
  constructor() {
    this.alive = false;
    this.species = 'GASKET';
    this.age = 0; this.lifetime = 0; this.fade = 0;
    this.centre = new Float32Array(3);
    this.planeU = new Float32Array(3); this.planeV = new Float32Array(3);
    this.radiusU = 0; this.radiusV = 0; this.orbitRate = 0; this.orbitPhase = 0;
    this.spinAxisA = new Float32Array(3); this.spinAxisB = new Float32Array(3);
    this.spinRateA = 0; this.spinRateB = 0; this.spinAngleA = 0; this.spinAngleB = 0;
    this.scale = 1; this.hue = 0; this.foldJitter = 0; this.glow = 1;
    this.breath = 0; this.breathRate = 0;
    this.growSeconds = 0; this.witherSeconds = 0;
  }

  spawn(rng, species, lifetime, spread, sizeScale) {
    this.species = species;
    this.lifetime = Math.max(lifetime, 0.5);
    this.growSeconds = this.lifetime * 0.1;
    this.witherSeconds = this.lifetime * 0.2;
    this.age = 0; this.fade = 0; this.alive = true;
    randomPlane(rng, this.planeU, this.planeV);
    this.radiusU = spread * (0.35 + 0.85 * rng.nextFloat());
    this.radiusV = spread * (0.35 + 0.85 * rng.nextFloat());
    this.orbitRate = (0.035 + 0.16 * rng.nextFloat()) * (rng.nextBoolean() ? 1 : -1);
    this.orbitPhase = rng.nextFloat() * 2 * Math.PI;
    randomUnitVector(rng, this.spinAxisA);
    randomUnitVector(rng, this.spinAxisB);
    this.spinRateA = (0.05 + 0.32 * rng.nextFloat()) * (rng.nextBoolean() ? 1 : -1);
    this.spinRateB = (0.03 + 0.19 * rng.nextFloat()) * (rng.nextBoolean() ? 1 : -1);
    this.spinAngleA = rng.nextFloat() * 2 * Math.PI;
    this.spinAngleB = rng.nextFloat() * 2 * Math.PI;
    this.scale = sizeScale * (0.55 + 0.9 * rng.nextFloat());
    this.hue = rng.nextFloat();
    this.foldJitter = rng.nextFloat() * 2 - 1;
    this.glow = 0.7 + 0.7 * rng.nextFloat();
    this.breath = rng.nextFloat() * 2 * Math.PI;
    this.breathRate = 0.08 + 0.22 * rng.nextFloat();
    this.advance(0, 1, 1);
  }

  advance(dt, motion, orbitScale, spinScale = 1) {
    if (!this.alive) return;
    this.age += dt;
    if (this.age >= this.lifetime) { this.alive = false; this.fade = 0; return; }
    const m = Math.max(motion, 0);
    const spin = m * Math.max(spinScale, 0);
    this.orbitPhase += dt * this.orbitRate * m * Math.max(orbitScale, 0) * 2 * Math.PI;
    this.spinAngleA += dt * this.spinRateA * spin * 2 * Math.PI;
    this.spinAngleB += dt * this.spinRateB * spin * 2 * Math.PI;
    this.breath += dt * this.breathRate * m;
    const c = Math.cos(this.orbitPhase), s = Math.sin(this.orbitPhase);
    for (let i = 0; i < 3; i++) {
      this.centre[i] = this.planeU[i] * this.radiusU * c + this.planeV[i] * this.radiusV * s;
    }
    this.fade = lifeEnvelope(this.age, this.lifetime, this.growSeconds, this.witherSeconds);
  }

  retire(fadeSeconds) {
    if (!this.alive) return;
    const fade = Math.max(fadeSeconds, 0.2);
    const end = this.age + fade;
    if (end < this.lifetime) {
      this.lifetime = end;
      this.witherSeconds = Math.min(this.witherSeconds, fade);
    }
  }

  writeRotation(out, offset) {
    worldToLocalRotation(this.spinAxisA, this.spinAngleA, this.spinAxisB, this.spinAngleB, out, offset);
  }
}

const SILENT_SPAWN_SECONDS = 2.5;
const SPAWN_GAP_SECONDS = 0.45;
const SPAWN_IMPULSE = 0.18;
const RETIRE_SECONDS = 1.6;

export class BloomBank {
  constructor(rng) {
    this.rng = rng;
    this.blooms = Array.from({ length: MAX_BLOOMS }, () => new Bloom());
    this.sinceSpawn = SILENT_SPAWN_SECONDS;
    this.sinceImpulse = 0;
  }

  get aliveCount() { return this.blooms.filter((b) => b.alive).length; }

  advance({ dt, target, impulse, species, lifetime, spread, sizeScale, motion, orbitScale, spinScale = 1 }) {
    for (const b of this.blooms) b.advance(dt, motion, orbitScale, spinScale);
    this.sinceSpawn += dt;
    this.sinceImpulse = impulse >= SPAWN_IMPULSE ? 0 : this.sinceImpulse + dt;

    const want = clamp(target, 0, MAX_BLOOMS);
    let living = this.aliveCount;
    if (living > want) {
      // Oldest first, skipping bodies already inside their retire window, so
      // the whole excess dissolves in one window (mirrors HyperspaceMath).
      let excess = living - want;
      while (excess > 0) {
        let victim = null;
        for (const b of this.blooms) {
          if (!b.alive) continue;
          if (b.lifetime - b.age <= RETIRE_SECONDS) continue;
          if (victim === null || b.age > victim.age) victim = b;
        }
        if (victim === null) break;
        victim.retire(RETIRE_SECONDS);
        excess--;
      }
    }
    if (living < want && this.sinceSpawn >= SPAWN_GAP_SECONDS) {
      const onHit = impulse >= SPAWN_IMPULSE;
      const onSilence = this.sinceImpulse >= SILENT_SPAWN_SECONDS;
      if (onHit || onSilence) {
        const slot = this.blooms.find((b) => !b.alive);
        if (slot) {
          const pick = species || SPECIES[this.rng.nextInt(SPECIES.length)];
          const life = lifetime * (0.65 + 0.7 * this.rng.nextFloat());
          slot.spawn(this.rng, pick, life, spread, sizeScale);
          this.sinceSpawn = 0;
          living++;
        }
      }
    }
  }

  snapshot(fold, pos, shape, look, rot, boundInflate = 0) {
    let n = 0;
    for (const b of this.blooms) {
      if (!b.alive || b.fade <= 0.002) continue;
      if (n >= MAX_BLOOMS) break;
      const i4 = n * 4;
      const worldScale = b.scale * (0.25 + 0.75 * b.fade);
      pos[i4] = b.centre[0];
      pos[i4 + 1] = b.centre[1];
      pos[i4 + 2] = b.centre[2];
      pos[i4 + 3] = localRadius(b.species) * worldScale + boundInflate;
      shape[i4] = SPECIES.indexOf(b.species);
      shape[i4 + 1] = worldScale;
      shape[i4 + 2] = foldFor(b.species, fold, b.foldJitter);
      shape[i4 + 3] = b.fade;
      look[i4] = b.hue;
      look[i4 + 1] = b.glow;
      look[i4 + 2] = b.breath;
      look[i4 + 3] = 0;
      b.writeRotation(rot, n * 9);
      n++;
    }
    return n;
  }
}

export const ACT_PROFILES = [
  { bodies: 2, field: 0.22, mirror: 0, camera: 6.5, motion: 0.45, glow: 0.7, hueSpread: 0.18 },
  { bodies: 3, field: 1.35, mirror: 1, camera: 9, motion: 0.8, glow: 1.1, hueSpread: 0.55 },
  { bodies: 5, field: 0.5, mirror: 0, camera: 5.4, motion: 1, glow: 1, hueSpread: 0.7 },
  { bodies: 7, field: 0.35, mirror: 0, camera: 4.2, motion: 1.15, glow: 1.2, hueSpread: 0.85 },
  { bodies: MAX_BLOOMS, field: 0.65, mirror: 0, camera: 5.2, motion: 1.5, glow: 1.45, hueSpread: 1 },
];

export const ACT_NAMES = ['Threshold', 'Chrysanthemum', 'Magic eye', 'Waiting room', 'Breakthrough'];

export function profileAt(act) {
  const x = clamp(act, 0, ACT_PROFILES.length - 1);
  const i = Math.min(Math.trunc(x), ACT_PROFILES.length - 2);
  const t = clamp(x - i, 0, 1);
  const a = ACT_PROFILES[i];
  const b = ACT_PROFILES[i + 1];
  const f = (u, v) => u + (v - u) * t;
  return {
    bodies: Math.round(f(a.bodies, b.bodies)),
    field: f(a.field, b.field),
    mirror: f(a.mirror, b.mirror),
    camera: f(a.camera, b.camera),
    motion: f(a.motion, b.motion),
    glow: f(a.glow, b.glow),
    hueSpread: f(a.hueSpread, b.hueSpread),
  };
}

export const RISE_SECONDS = 26;
export const FALL_SECONDS = 44;
export const IMMERSION_PIVOT = 0.42;
export const MIN_ACT_SECONDS = 4;
export const ACT_GLIDE_SECONDS = 2.5;
export const JOURNEY_MUSIC = 0;
export const JOURNEY_HOLD = 1;
export const JOURNEY_CYCLE = 2;

export class HyperspaceJourney {
  constructor() { this.immersion = 0; this.actPosition = 0; this.act = 0; this.heldSeconds = 0; this.cyclePhase = 0; }

  advance({ dt, energy, mode, holdAct, cycleSeconds, pace }) {
    const last = ACT_PROFILES.length - 1;
    const step = dt * Math.max(pace, 0);
    let goal;
    if (mode === JOURNEY_HOLD) {
      goal = clamp(holdAct, 0, last);
    } else if (mode === JOURNEY_CYCLE) {
      // Hold on a timer: one whole act per cycleSeconds, ping-ponged
      // 0,1,2,3,4,3,2,1 so every step is to a neighbouring act and no act
      // gets more of the clock than the control promises it.
      const per = Math.max(cycleSeconds, 2);
      const slots = Math.max(2 * last, 1);
      this.cyclePhase = (this.cyclePhase + step / per) % slots;
      const slot = clamp(Math.trunc(this.cyclePhase), 0, slots - 1);
      goal = last - Math.abs(last - slot);
    } else {
      const drive = clamp(energy, 0, 1) - IMMERSION_PIVOT;
      const rate = drive >= 0
        ? drive / (1 - IMMERSION_PIVOT) / RISE_SECONDS
        : drive / IMMERSION_PIVOT / FALL_SECONDS;
      this.immersion = clamp(this.immersion + rate * step, 0, 1);
      goal = this.immersion * last;
    }
    this.actPosition += (goal - this.actPosition) * smoothing(step, ACT_GLIDE_SECONDS);
    this.actPosition = clamp(this.actPosition, 0, last);
    this.heldSeconds += dt;
    const rounded = clamp(Math.round(this.actPosition), 0, last);
    if (rounded !== this.act && this.heldSeconds >= MIN_ACT_SECONDS) {
      this.act = rounded;
      this.heldSeconds = 0;
    }
  }

  profile() { return profileAt(this.actPosition); }
}

export class HyperspaceCamera {
  constructor() { this.position = new Float32Array(3); this.basis = new Float32Array(9); this.t = 0; }

  advance({ dt, distance, drift }) {
    // Wrapped at 1000 turns of 2*pi (HyperspaceMath.TIME_WRAP_SECONDS): all
    // five rates are multiples of 0.001, so the wrap is a whole turn each.
    this.t = (this.t + dt * Math.max(drift, 0)) % 6283.1853;
    const t = this.t;
    const yaw = 0.11 * t + 0.37 * Math.sin(0.073 * t) + 0.13 * Math.sin(0.191 * t);
    const pitch = 0.42 * Math.sin(0.041 * t) + 0.17 * Math.sin(0.113 * t);
    const d = Math.max(distance, 0.35);
    const cp = Math.cos(pitch);
    const p = this.position;
    p[0] = d * cp * Math.cos(yaw);
    p[1] = d * Math.sin(pitch);
    p[2] = d * cp * Math.sin(yaw);
    const inv = 1 / Math.max(Math.hypot(p[0], p[1], p[2]), 1e-5);
    const fx = -p[0] * inv, fy = -p[1] * inv, fz = -p[2] * inv;
    const upIsY = Math.abs(fy) < 0.985;
    const ux = upIsY ? 0 : 1, uy = upIsY ? 1 : 0, uz = 0;
    let rx = fy * uz - fz * uy;
    let ry = fz * ux - fx * uz;
    let rz = fx * uy - fy * ux;
    const rl = 1 / Math.max(Math.hypot(rx, ry, rz), 1e-5);
    rx *= rl; ry *= rl; rz *= rl;
    // No roll: Rotation belongs to the composite pass for this scene family.
    const vx = ry * fz - rz * fy;
    const vy = rz * fx - rx * fz;
    const vz = rx * fy - ry * fx;
    const b = this.basis;
    b[0] = rx; b[1] = ry; b[2] = rz;
    b[3] = vx; b[4] = vy; b[5] = vz;
    b[6] = fx; b[7] = fy; b[8] = fz;
  }
}

/** MarchBudget.forDetail: the slider's range mapped onto the shader's bounds. */
export function marchBudget(detail) {
  const t = clamp((detail - 0.25) / (1.5 - 0.25), 0, 1);
  const lerp = (floor, top) => Math.round(floor + (top - floor) * t);
  return {
    steps: lerp(64, 128),
    iterations: lerp(5, 14),
    bulbIterations: lerp(3, 8),
    seedIterations: lerp(5, 12),
  };
}

export const Look = {
  HIT_EPSILON: 0.0016,
  BOUND_MARGIN: 0.12,
  spread: (bodies) => 1.1 + 0.22 * bodies,
  bodySize: (bodies) => Math.max(0.72 - 0.045 * bodies, 0.26),
  maxBodyRadius: (bodies) => Look.bodySize(bodies) * 1.45 * MAX_LOCAL_RADIUS,
  cameraDistance: (actCamera, spread, maxBodyRadius) => Math.max(actCamera, spread + maxBodyRadius + 0.9),
  bodyTarget: (profileBodies, density) => clamp(Math.round(profileBodies * clamp(density, 0.1, 2)), 1, MAX_BLOOMS),
  farPlane: (camera, spread) => camera + spread + 6,
  maxMarchStep: (scale) => Math.max(scale, 0.05),
};

export const MeltMath = {
  DEFAULT_SCALE: 2.6,
  BODY_FORCE: 220,
  TOUCH_FORCE: 320,
  TOUCH_RADIUS: 0.13,
  BIRTH_BOOST: 1.8,
  MELT_SECONDS: 0.09,
  // The dye splat is additive against a decay divisor, so a texel settles at
  // injection/(dissipation*dt) - unbounded as the dissipation falls. The
  // ceiling is applied at injection, which bounds the whole field: no other
  // pass can raise its maximum.
  DYE_CEILING: 1,
  DYE_FADE_RATIO: 0.45,
  MIN_DYE_DISSIPATION: 0.08,
  dyeDissipation: (flowFade) =>
    clamp(clamp(flowFade, 0, 4) * MeltMath.DYE_FADE_RATIO, MeltMath.MIN_DYE_DISSIPATION, 4),
  simFromWorld: (world, scale) => world / Math.max(scale, 0.05),
  insideSim: (x, y, aspect) => Math.abs(x) <= Math.max(aspect, 0.05) + 0.25 && Math.abs(y) <= 1.25,
  splatRadius: (worldRadius, scale) => clamp(worldRadius / Math.max(scale, 0.05), 0.05, 0.5),
  birthBoost: (life) => 1 + (MeltMath.BIRTH_BOOST - 1) * (1 - clamp(life, 0, 1)),
  reach: (melt, scale) => clamp(melt, 0, 2) * Math.max(scale, 0.05) * 0.25,
  stepRelaxation: (melt) => 1 / (1 + 1.6 * clamp(melt, 0, 2)),
};

export const FluidHue = {
  MIN_HUE_RANGE: 0.1,
  MAX_HUE_RANGE: 1.5,
  wrap01(h) { const x = h - Math.floor(h); return x >= 1 ? 0 : x; },
  base(paletteBase) { return FluidHue.wrap01(paletteBase); },
  range(hueRange) { return clamp(hueRange, FluidHue.MIN_HUE_RANGE, FluidHue.MAX_HUE_RANGE); },
  span(hueRange, paletteRange) { return FluidHue.range(hueRange) * clamp(paletteRange, 0, 1); },
  rgb(hue, saturation) {
    const h = FluidHue.wrap01(hue);
    const s = clamp(saturation, 0, 1);
    const v = 1;
    const sextant = h * 6;
    const i = Math.trunc(sextant) % 6;
    const fr = sextant - Math.trunc(sextant);
    const p = v * (1 - s), q = v * (1 - fr * s), t = v * (1 - (1 - fr) * s);
    switch (i) {
      case 0: return [v, t, p];
      case 1: return [q, v, p];
      case 2: return [p, v, t];
      case 3: return [p, q, v];
      case 4: return [t, p, v];
      default: return [v, p, q];
    }
  },
};

/** SceneParams.DEFAULT, hyperspace subset plus the shared controls it reads. */
export const DEFAULT_PARAMS = {
  speed: 1,
  zoom: 1,
  rotation: 0,
  audioDrive: 1,
  beatResponse: 1,
  hueRange: 1,
  paletteBase: 0,
  paletteRange: 1,
  hyperJourney: 0,
  hyperAct: 2,
  hyperCycleSeconds: 30,
  hyperBodies: 1,
  hyperLifetime: 14,
  hyperSpin: 1,
  hyperOrbit: 1,
  hyperSpecies: 0,
  hyperFold: 0.5,
  hyperDetail: 1,
  hyperGlow: 1,
  hyperNeon: 1,
  hyperField: 1,
  hyperHaze: 0.7,
  hyperCamera: 1,
  hyperMirrorFolds: 6,
  hyperTrap: 0.8,
  hyperMelt: 0.55,
  hyperStain: 0.5,
  hyperLiquid: 0.35,
  hyperRidges: 0.5,
  hyperStir: 1,
  hyperSwirl: 26,
  hyperFlowFade: 0.35,
};
