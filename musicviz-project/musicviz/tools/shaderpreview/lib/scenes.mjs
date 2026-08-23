// Per-frame uniform plans, mirroring the Kotlin scenes' draw() methods.
//
// Each driver returns, for every frame: the uniform map the shader will be
// given, the set of uniform NAMES it claims to supply (audited against the
// Kotlin source by lib/kotlin.mjs), and any per-frame side data the harness
// page needs (the melt's splat queue).
//
// Uniform values are tagged with their setter so the page can upload them
// without guessing: '1f' '1i' '2f' '3f' '1fv' '4fv' 'm3fv' 'tex'.

import * as H from './hyperspace-math.mjs';
import { MeltEmitters } from './emitters.mjs';
import { audioTexRows, motionImpulse, beatImpulseOf } from './audio.mjs';

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

// ---------------------------------------------------------------------------
// HYPERSPACE - render/scene/HyperspaceScene.kt
// ---------------------------------------------------------------------------

const IDLE_RMS = 0.015;
const IDLE_FADE_SECONDS = 1.5;
const IDLE_CYCLE_SECONDS = 150;
const IDLE_IMPULSE = 0.35;
const IDLE_IMPULSE_SECONDS = 2.2;
const FOV = 0.85;
const EXPOSURE = 1.45;
const BODY_INK = 0.22;

/** MeltField's grid configuration. */
const MELT_SIM_RES = 96;
const MELT_DYE_RES = 256;
const MELT_PRESSURE_ITERATIONS = 14;

export function createHyperspaceDriver({ params, width, height, seed = 12345, hasMelt = true }) {
  const p = { ...H.DEFAULT_PARAMS, ...params };
  const rng = H.makeRng(seed);
  const rnd = () => rng.nextFloat();
  const journey = new H.HyperspaceJourney();
  const camera = new H.HyperspaceCamera();
  const bank = new H.BloomBank(rng);
  const emitters = new MeltEmitters();

  const bloomPos = new Float32Array(H.MAX_BLOOMS * 4);
  const bloomShape = new Float32Array(H.MAX_BLOOMS * 4);
  const bloomLook = new Float32Array(H.MAX_BLOOMS * 4);
  const bloomRot = new Float32Array(H.MAX_BLOOMS * 9);
  const prevBodyXy = new Float32Array(H.MAX_BLOOMS * 2);
  const hasPrevBody = new Array(H.MAX_BLOOMS).fill(false);

  let time = 0;
  let beatPulse = 0;
  let idleBlend = 0;
  let idlePhase = 0;
  let idleImpulseAge = 0;

  const meltAspect = width / Math.max(height, 1);
  // FluidSim.flowScale = 2 * rdx / velocityHeight, rdx = 1/cellSize,
  // cellSize = 2/velocityHeight  =>  flowScale = velocityHeight^2 / 2... no:
  // rdx = velocityHeight/2, so flowScale = 2*(vh/2)/vh = 1. Kept explicit so
  // a change to FluidSim's grid maths shows up here as a mismatch.
  const velGrid = fluidResolution(MELT_SIM_RES, width, height);
  const dyeGrid = fluidResolution(MELT_DYE_RES, width, height);
  const cellSize = 2 / velGrid[1];
  const flowScale = (2 * (1 / cellSize)) / velGrid[1];

  const supplies = new Set([
    'uResolution', 'uTime', 'uStyle', 'uBloomCount', 'uBloomPos', 'uBloomShape', 'uBloomLook', 'uBloomRot',
    'uCamPos', 'uCamBasis', 'uFov', 'uSteps', 'uIters', 'uBulbIters', 'uSeedIters',
    'uFar', 'uMaxStep', 'uHitEps',
    'uBoundMargin', 'uField', 'uMirror', 'uMirrorFolds', 'uGlow', 'uNeon', 'uHaze',
    'uTrapColor', 'uHueSpread', 'uBaseHue', 'uHueSpan', 'uHasMelt', 'uMelt', 'uFlowGain',
    'uMeltReach', 'uMeltScale', 'uMeltAspect', 'uMeltRelax', 'uStain', 'uLiquid', 'uRidges',
    'uFlowTex', 'uDyeTex', 'uEnergy', 'uBass', 'uTreble', 'uBeat', 'uExposure',
    // The substyle identity block (catalog-driven in the app; the harness
    // previews shader style 0, so these carry the Original's neutral values
    // except the live envelopes, which are mirrored from HyperspaceScene).
    'uLipschitz', 'uStyleFloor', 'uStyleKaleido', 'uStyleTint',
    'uSlewBass', 'uSlewMid', 'uStylePhase', 'uBands',
  ]);

  // HyperspaceScene's slew-limited envelopes, phase and spectrum summary.
  const TIME_WRAP_SECONDS = 6283.1853;
  const SLEW_RISE_PER_SEC = 2.2;
  const SLEW_FALL_PER_SEC = 1.1;
  let slewBass = 0;
  let slewMid = 0;
  let stylePhase = 0;
  const bands16 = new Float32Array(16);

  function slewLimit(current, target, dt, rise, fall) {
    const t = clamp(target, 0, 1);
    return clamp(current + clamp(t - current, -fall * dt, rise * dt), 0, 1);
  }

  function advanceBands(bands, dt) {
    const src = bands && bands.length ? bands : null;
    for (let i = 0; i < 16; i++) {
      let goal = 0;
      if (src) {
        const lo = Math.floor((i * src.length) / 16);
        const hi = Math.min(Math.max(Math.floor(((i + 1) * src.length) / 16), lo + 1), src.length);
        let sum = 0;
        for (let j = lo; j < hi; j++) sum += clamp(src[j], 0, 1.5);
        goal = sum / (hi - lo);
      }
      const seconds = goal > bands16[i] ? 0.06 : 0.32;
      const k = 1 - Math.exp(-dt / Math.max(seconds, 1e-6));
      const next = bands16[i] + (goal - bands16[i]) * k;
      bands16[i] = Number.isFinite(next) ? clamp(next, 0, 1.5) : 0;
    }
  }

  function step(features, dt) {
    time = (time + dt) % TIME_WRAP_SECONDS;
    const f = features;
    const impulseRaw = motionImpulse(f);
    const pace = clamp(p.speed, 0.05, 4);
    slewBass = slewLimit(slewBass, f.bass, dt, SLEW_RISE_PER_SEC, SLEW_FALL_PER_SEC);
    slewMid = slewLimit(slewMid, f.mid, dt, SLEW_RISE_PER_SEC, SLEW_FALL_PER_SEC);
    advanceBands(f.bands, dt);
    stylePhase = (stylePhase + dt * pace * 0.05) % 1;

    const silent = f.rms < IDLE_RMS;
    const fadeStep = IDLE_FADE_SECONDS > 0 ? dt / IDLE_FADE_SECONDS : 1;
    idleBlend = clamp(idleBlend + (silent ? fadeStep : -fadeStep * 3), 0, 1);
    idlePhase += dt / IDLE_CYCLE_SECONDS;
    const live = (f.macroEnergy > 0 ? f.macroEnergy : f.rms) * clamp(p.audioDrive, 0, 4);
    const idle = 0.5 - 0.5 * Math.cos(idlePhase * 2 * Math.PI);
    const energy = clamp(live * (1 - idleBlend) + idle * idleBlend, 0, 1);

    journey.advance({
      dt, energy, mode: p.hyperJourney, holdAct: p.hyperAct,
      cycleSeconds: p.hyperCycleSeconds, pace,
    });
    const profile = journey.profile();

    const target = H.Look.bodyTarget(profile.bodies, p.hyperBodies);
    const spread = H.Look.spread(target);
    idleImpulseAge += dt;
    let impulse = clamp(impulseRaw * clamp(p.beatResponse, 0, 2), 0, 1.5);
    if (idleBlend > 0.5 && idleImpulseAge >= IDLE_IMPULSE_SECONDS) {
      impulse = Math.max(impulse, IDLE_IMPULSE);
      idleImpulseAge = 0;
    } else if (impulse > 0.2) {
      idleImpulseAge = 0;
    }
    bank.advance({
      dt, target, impulse,
      species: p.hyperSpecies <= 0 ? null : H.SPECIES[p.hyperSpecies - 1],
      lifetime: clamp(p.hyperLifetime, 2, 60),
      spread,
      sizeScale: H.Look.bodySize(target),
      // Decoupled like the app: spin no longer freezes orbits and breath.
      motion: profile.motion * pace,
      orbitScale: clamp(p.hyperOrbit, 0, 3),
      spinScale: clamp(p.hyperSpin, 0, 3),
    });

    const meltAmount = hasMelt ? clamp(p.hyperMelt, 0, 2) : 0;
    const hueBase = H.FluidHue.base(p.paletteBase);
    const hueSpan = H.FluidHue.span(p.hueRange, p.paletteRange);

    let splats = [];
    if (hasMelt) {
      splats = splats.concat(stirWithBodies(hueBase, hueSpan));
      // MeltField.step: emitters then the sim, at the sim's own clamped dt.
      const simDt = clamp(dt, 0, 1 / 30);
      emitters.forceScale = clamp(p.hyperStir, 0, 3);
      emitters.stirrerSpeed = clamp(p.speed, 0.1, 2);
      emitters.beatResponse = p.beatResponse;
      splats = splats.concat(emitters.tick(f, simDt, meltAspect, hueBase, hueSpan, impulseRaw));
    }

    const bloomCount = bank.snapshot(
      p.hyperFold, bloomPos, bloomShape, bloomLook, bloomRot,
      H.MeltMath.reach(meltAmount, H.MeltMath.DEFAULT_SCALE),
    );

    // Zoom and Rotation are the composite pass' for this family, so the scene
    // reads neither - see HyperspaceScene's camera block.
    const camDistance = H.Look.cameraDistance(
      profile.camera, spread, H.Look.maxBodyRadius(target),
    );
    camera.advance({ dt, distance: camDistance, drift: clamp(p.hyperCamera, 0, 3) * pace });
    const farPlane = H.Look.farPlane(camDistance, spread);
    beatPulse = clamp(Math.max(impulseRaw * clamp(p.beatResponse, 0, 2), beatPulse - dt * 3), 0, 1.5);
    const budget = H.marchBudget(p.hyperDetail);

    const uniforms = {
      uResolution: { t: '2f', v: [width, height] },
      uTime: { t: '1f', v: time },
      uStyle: { t: '1i', v: 0 },
      uBloomCount: { t: '1i', v: bloomCount },
      uBloomPos: { t: '4fv', v: Array.from(bloomPos) },
      uBloomShape: { t: '4fv', v: Array.from(bloomShape) },
      uBloomLook: { t: '4fv', v: Array.from(bloomLook) },
      uBloomRot: { t: 'm3fv', v: Array.from(bloomRot) },
      uCamPos: { t: '3f', v: Array.from(camera.position) },
      uCamBasis: { t: 'm3fv', v: Array.from(camera.basis) },
      uFov: { t: '1f', v: FOV },
      uSteps: { t: '1i', v: budget.steps },
      uIters: { t: '1i', v: budget.iterations },
      uBulbIters: { t: '1i', v: budget.bulbIterations },
      uSeedIters: { t: '1i', v: budget.seedIterations },
      uFar: { t: '1f', v: farPlane },
      uMaxStep: { t: '1f', v: H.Look.maxMarchStep(H.MeltMath.DEFAULT_SCALE) },
      uHitEps: { t: '1f', v: H.Look.HIT_EPSILON },
      uBoundMargin: { t: '1f', v: H.Look.BOUND_MARGIN },
      uField: { t: '1f', v: profile.field * clamp(p.hyperField, 0, 2) },
      uMirror: { t: '1f', v: profile.mirror },
      uMirrorFolds: { t: '1f', v: clamp(Math.round(p.hyperMirrorFolds), 2, 16) },
      uGlow: { t: '1f', v: profile.glow * clamp(p.hyperGlow, 0, 2) },
      uNeon: { t: '1f', v: clamp(p.hyperNeon, 0, 2) },
      uHaze: { t: '1f', v: clamp(p.hyperHaze, 0, 2) },
      uTrapColor: { t: '1f', v: clamp(p.hyperTrap, 0, 1.5) },
      uHueSpread: { t: '1f', v: profile.hueSpread },
      uBaseHue: { t: '1f', v: hueBase },
      uHueSpan: { t: '1f', v: hueSpan },
      uHasMelt: { t: '1f', v: hasMelt ? 1 : 0 },
      uMelt: { t: '1f', v: meltAmount },
      uFlowGain: { t: '1f', v: flowScale * H.MeltMath.DEFAULT_SCALE * H.MeltMath.MELT_SECONDS },
      uMeltReach: { t: '1f', v: H.MeltMath.reach(meltAmount, H.MeltMath.DEFAULT_SCALE) },
      uMeltScale: { t: '1f', v: H.MeltMath.DEFAULT_SCALE },
      uMeltAspect: { t: '1f', v: meltAspect },
      uMeltRelax: { t: '1f', v: H.MeltMath.stepRelaxation(meltAmount) },
      uStain: { t: '1f', v: hasMelt ? clamp(p.hyperStain, 0, 1.5) : 0 },
      uLiquid: { t: '1f', v: hasMelt ? clamp(p.hyperLiquid, 0, 1.5) : 0 },
      uRidges: { t: '1f', v: hasMelt ? clamp(p.hyperRidges, 0, 1) : 0 },
      uFlowTex: { t: 'tex', v: 0 },
      uDyeTex: { t: 'tex', v: 1 },
      uEnergy: { t: '1f', v: clamp(f.rms, 0, 1.5) },
      uBass: { t: '1f', v: clamp(f.bass, 0, 1.5) },
      uTreble: { t: '1f', v: clamp(f.treble, 0, 1.5) },
      uBeat: { t: '1f', v: beatPulse },
      uExposure: { t: '1f', v: EXPOSURE },
      // Substyle identity block: neutral (Original) constants, live envelopes.
      uLipschitz: { t: '1f', v: 1 },
      uStyleFloor: { t: '1f', v: 0 },
      uStyleKaleido: { t: '1f', v: 0 },
      uStyleTint: { t: '3f', v: [0, 0.7, 0] },
      uSlewBass: { t: '1f', v: slewBass },
      uSlewMid: { t: '1f', v: slewMid },
      uStylePhase: { t: '1f', v: stylePhase },
      uBands: { t: '1fv', v: Array.from(bands16) },
    };

    return {
      uniforms,
      melt: hasMelt ? {
        splats,
        dt: clamp(dt, 0, 1 / 30),
        curlStrength: clamp(p.hyperSwirl, 0, 50) * (1 + 0.5 * f.mid),
        velocityDissipation: clamp(p.hyperFlowFade, 0, 4),
        densityDissipation: H.MeltMath.dyeDissipation(p.hyperFlowFade),
        dyeCeiling: H.MeltMath.DYE_CEILING,
      } : null,
      debug: {
        time,
        act: journey.act,
        actName: H.ACT_NAMES[journey.act],
        actPosition: journey.actPosition,
        bloomCount,
        species: bank.blooms.filter((b) => b.alive).map((b) => b.species),
        camDistance,
      },
    };
  }

  function stirWithBodies(hueBase, hueSpan) {
    const out = [];
    const strength = clamp(p.hyperStain, 0, 1.5) + clamp(p.hyperLiquid, 0, 1.5);
    const blooms = bank.blooms;
    if (strength <= 0.01) {
      for (let i = 0; i < blooms.length; i++) {
        const b = blooms[i];
        if (!b.alive) { hasPrevBody[i] = false; continue; }
        prevBodyXy[i * 2] = b.centre[0];
        prevBodyXy[i * 2 + 1] = b.centre[1];
        hasPrevBody[i] = true;
      }
      return out;
    }
    for (let i = 0; i < blooms.length; i++) {
      const b = blooms[i];
      if (!b.alive || b.fade <= 0.01) { hasPrevBody[i] = false; continue; }
      const x = b.centre[0];
      const y = b.centre[1];
      if (hasPrevBody[i]) {
        const [r, g, bl] = H.FluidHue.rgb(hueBase + b.hue * hueSpan, 0.95);
        const scale = H.MeltMath.DEFAULT_SCALE;
        const st = BODY_INK * strength * b.fade;
        const px = H.MeltMath.simFromWorld(prevBodyXy[i * 2], scale);
        const py = H.MeltMath.simFromWorld(prevBodyXy[i * 2 + 1], scale);
        const cx = H.MeltMath.simFromWorld(x, scale);
        const cy = H.MeltMath.simFromWorld(y, scale);
        if (st > 0 && H.MeltMath.insideSim(cx, cy, meltAspect)) {
          const edge = H.MeltMath.birthBoost(b.fade);
          const push = H.MeltMath.BODY_FORCE * st * edge;
          out.push({
            prevX: px, prevY: py, curX: cx, curY: cy,
            radius: H.MeltMath.splatRadius(H.localRadius(b.species) * b.scale * b.fade, scale),
            velX: (cx - px) * push, velY: (cy - py) * push,
            r: r * st * edge, g: g * st * edge, b: bl * st * edge,
          });
        }
      }
      prevBodyXy[i * 2] = x;
      prevBodyXy[i * 2 + 1] = y;
      hasPrevBody[i] = true;
    }
    return out;
  }

  /**
   * Advances only the FREE-RUNNING CLOCKS by [seconds], without stepping any
   * per-frame simulation.
   *
   * A live wallpaper's `time` reaches hours, and everything downstream of it
   * is a float: `sin(uTime * 0.043)` at t = 3600 has lost most of its
   * fractional precision in a mediump-capable driver, and `p.rotation * time`
   * grows without bound. That is what this mode is for. It deliberately does
   * NOT age the body bank or the fluid - those integrate at a bounded dt in
   * the app too, and pretending to run them at a 60-second step would produce
   * a picture the app can never show. Anything about the bodies read from a
   * jumped run is meaningless.
   */
  function jumpClock(seconds) {
    time += seconds;
    camera.t += seconds * clamp(p.hyperCamera, 0, 3) * clamp(p.speed, 0.05, 4);
  }

  return {
    id: 'hyperspace',
    supplies,
    step,
    jumpClock,
    meltConfig: {
      enabled: hasMelt,
      velWidth: velGrid[0], velHeight: velGrid[1],
      dyeWidth: dyeGrid[0], dyeHeight: dyeGrid[1],
      aspect: meltAspect,
      cellSize,
      pressureIterations: MELT_PRESSURE_ITERATIONS,
      pressureDamp: 0.8,
    },
  };
}

/** FluidBuffers.resolution. */
function fluidResolution(res, width, height) {
  if (width <= 0 || height <= 0) return [res, res];
  const aspect = width / height;
  return aspect >= 1
    ? [Math.max(2, Math.round(res * aspect)), res]
    : [res, Math.max(2, Math.round(res / aspect))];
}

// ---------------------------------------------------------------------------
// The GLSL styles - render/scene/ShaderScene.kt
// ---------------------------------------------------------------------------

/** TouchField.MAX_POINTS - the slot count uTouchPoints is declared with. */
const TOUCH_MAX_POINTS = 5;

const SHADER_SCENE_DEFAULTS = {
  speed: 1, zoom: 1, rotation: 0, endlessZoom: false, endlessZoomSpeed: 0.2,
  colorCycle: false, cycleSpeed: 0.2, audioDrive: 1, colorShift: 0, hueRange: 1,
  saturation: 1, brightness: 1, invert: false, intensity: 1, mirror: false,
  beatResponse: 1, turbulence: 0, paletteBase: 0, paletteRange: 1,
  palette2Base: 0.5, palette2Range: 0.45, paletteMix: 0, duotone: false,
  bloom: 0, warp: 0, ripple: 0, symmetry: 0, kaleidoscope: false, morph: 0,
  pixelate: 0, posterize: 0, sway: 0, pulse: 0, driftX: 0, driftY: 0, shake: 0,
  tile: 0, twist: 0, temperature: 0, solarize: false, flash: 0, contrast: 1,
  gamma: 1, paletteLut: -1,
  // The Detail control. It is `hyperDetail` because it is the SAME slider
  // HYPERSPACE scales itself with - ShaderScene sends MarchBudget.forDetail of
  // it as uSteps, so one control drives every marched style.
  hyperDetail: 1,
};

export function createShaderSceneDriver({ params, width, height }) {
  const p = { ...SHADER_SCENE_DEFAULTS, ...params };
  let shaderTime = 0;
  let rotationAngle = 0;
  let zoomPhase = 0;
  let cyclePhase = 0;
  let beatPulse = 0;
  let beatPhase = 0;

  const supplies = new Set([
    'uTime', 'uBass', 'uMid', 'uTreble', 'uEnergy', 'uBeat', 'uSpeed', 'uZoom', 'uRotation',
    'uZoomPhase', 'uColorShift', 'uHueRange', 'uSat', 'uBright', 'uInvert', 'uIntensity',
    'uMirrorX', 'uBeatResponse', 'uTurbulence', 'uPalBase', 'uPalRange', 'uPal2Base',
    'uPal2Range', 'uPaletteMix', 'uDuotone', 'uBloom', 'uWarp', 'uRipple', 'uSymmetry',
    'uKaleido', 'uMorph', 'uPixelate', 'uPosterize', 'uSway', 'uPulse', 'uBeatPhase',
    'uDriftX', 'uDriftY', 'uShake', 'uTile', 'uTwist', 'uTemperature', 'uSolarize', 'uFlash',
    'uContrast', 'uGamma', 'uResolution', 'uAudioTex', 'uPalLutMix', 'uPalLutRow',
    // The Detail budget, and where the fingers are (TouchField -> uploadTouch).
    // Supplied UNTOUCHED here: this harness has no pointer, and the untouched
    // state is a real state the app is in for most of its life, not a
    // stand-in. Anything a style draws only under a finger is therefore
    // invisible to this tool - see README, "What it cannot tell you".
    'uSteps', 'uTouchAnchor', 'uTouchPoints', 'uTouchCount', 'uTouchGesture',
    'uTouchAxis', 'uTouchSpin',
    // Conditional in the app: the renderer only binds the FlowField for the
    // scenes wired to it, and the cyclic-palette atlas only when it loaded.
    // Both are supplied here as the NEUTRAL state the app itself sends when
    // they are absent (zero flow at zero strength, atlas mix 0) rather than
    // left unset, because unset means "sampler reads unit 0" - which on this
    // family is the audio texture, i.e. a shader would silently read the
    // spectrum as a colour map.
    'uFlow', 'uFlowStrength', 'uPalLut',
  ]);

  function step(f, dt) {
    shaderTime += p.speed * dt;
    rotationAngle += p.rotation * dt;
    zoomPhase = p.endlessZoom ? (zoomPhase + p.endlessZoomSpeed * dt) % 1 : 0;
    if (p.colorCycle) cyclePhase = (cyclePhase + p.cycleSpeed * dt) % 1;
    const drive = p.audioDrive;
    const impulse = motionImpulse(f);
    beatPulse = Math.max(0, Math.max(impulse, beatPulse - dt * 3));
    if (f.bpm > 40) {
      beatPhase = (beatPhase + (dt * f.bpm) / 60) % 1;
      if (f.beat) {
        beatPhase = beatPhase > 0.5 ? beatPhase * 0.5 + 0.5 : beatPhase * 0.5;
        if (beatPhase >= 0.999) beatPhase = 0;
      }
    } else {
      beatPhase = (beatPhase + dt) % 1;
    }
    const u = (name, v) => ({ [name]: { t: '1f', v } });
    const uniforms = Object.assign(
      {},
      u('uTime', shaderTime),
      u('uBass', clamp(f.bass * drive, 0, 1.5)),
      u('uMid', clamp(f.mid * drive, 0, 1.5)),
      u('uTreble', clamp(f.treble * drive, 0, 1.5)),
      u('uEnergy', clamp(f.rms * drive, 0, 1.5)),
      u('uBeat', beatPulse),
      u('uSpeed', p.speed),
      u('uZoom', p.zoom),
      u('uRotation', rotationAngle),
      u('uZoomPhase', zoomPhase),
      u('uColorShift', p.colorShift + cyclePhase),
      u('uHueRange', p.hueRange),
      u('uSat', p.saturation),
      u('uBright', p.brightness),
      u('uInvert', p.invert ? 1 : 0),
      u('uIntensity', p.intensity),
      u('uMirrorX', p.mirror ? 1 : 0),
      u('uBeatResponse', p.beatResponse),
      u('uTurbulence', p.turbulence),
      u('uPalBase', p.paletteBase),
      u('uPalRange', p.paletteRange),
      u('uPal2Base', p.palette2Base),
      u('uPal2Range', p.palette2Range),
      u('uPaletteMix', p.paletteMix),
      u('uDuotone', p.duotone ? 1 : 0),
      u('uBloom', p.bloom),
      u('uWarp', p.warp),
      u('uRipple', p.ripple),
      u('uSymmetry', p.symmetry),
      u('uKaleido', p.kaleidoscope ? 1 : 0),
      u('uMorph', p.morph),
      u('uPixelate', p.pixelate),
      u('uPosterize', p.posterize),
      u('uSway', p.sway),
      u('uPulse', p.pulse),
      u('uBeatPhase', beatPhase),
      u('uDriftX', p.driftX),
      u('uDriftY', p.driftY),
      u('uShake', p.shake),
      u('uTile', p.tile),
      u('uTwist', p.twist),
      u('uTemperature', p.temperature),
      u('uSolarize', p.solarize ? 1 : 0),
      u('uFlash', p.flash),
      u('uContrast', p.contrast),
      u('uGamma', p.gamma),
      // The cyclic-palette atlas is a binary resource this tool does not
      // decode; the app forces the mix to 0 when the atlas is missing, which
      // is the same state.
      u('uPalLutMix', 0),
      u('uPalLutRow', 0),
      u('uFlowStrength', 0),
      // A float, not an int: it is a budget the loop BREAKS on, while the loop
      // bound stays a compile-time constant. HyperspaceScene sends its own
      // uSteps as an int to a different program; same name, different contract.
      u('uSteps', H.marchBudget(p.hyperDetail).steps),
      u('uTouchSpin', 0),
      {
        uResolution: { t: '2f', v: [width, height] },
        uAudioTex: { t: 'tex', v: 0 },
        uFlow: { t: 'tex', v: 1 },
        uPalLut: { t: 'tex', v: 2 },
        // Untouched: TouchField publishes exactly these values when no finger
        // has ever been down, so this is the app's own zero and not an
        // invented one. Gesture 0 is TouchField.GESTURE_NONE.
        uTouchAnchor: { t: '4f', v: [0, 0, 0, 0] },
        uTouchPoints: { t: '4fv', v: new Array(TOUCH_MAX_POINTS * 4).fill(0) },
        uTouchCount: { t: '1i', v: 0 },
        uTouchGesture: { t: '1i', v: 0 },
        uTouchAxis: { t: '2f', v: [0, 0] },
      },
    );
    return {
      uniforms,
      audioTex: Array.from(audioTexRows(f, drive)),
      melt: null,
      debug: { time: shaderTime, beatPulse },
    };
  }

  /** See the hyperspace driver's jumpClock: free-running clocks only. */
  function jumpClock(seconds) {
    shaderTime += p.speed * seconds;
    rotationAngle += p.rotation * seconds;
    if (p.endlessZoom) zoomPhase = (zoomPhase + p.endlessZoomSpeed * seconds) % 1;
    if (p.colorCycle) cyclePhase = (cyclePhase + p.cycleSpeed * seconds) % 1;
  }

  return { id: 'shader', supplies, step, jumpClock, meltConfig: { enabled: false } };
}

// ---------------------------------------------------------------------------
// The four FIELD-SIM families - SILK / LIFE / ACID / MYCO
// ---------------------------------------------------------------------------
//
// Each of these scenes is a multipass GPU state machine: one or two ping-pong
// textures stepped by the app's own fragment shaders, then a present pass.
// The drivers below mirror the corresponding Kotlin draw() methods
// (render/scene/{Silk,Life,Acid,Myco}Scene.kt) uniform for uniform, and emit
// a PER-FRAME PASS LIST the page executes generically: each pass names a
// program, a target ('screen' or a ping-pong name), the textures to bind,
// the draw call and the blend state. page/harness.html owns nothing but GL.
//
// Constants are copied from the Kotlin with the comment that justifies them,
// so a drift is visible in a diff. Style tables are verbatim mirrors of
// render/scene/VisualStyleCatalog.kt (silk/life/acid/myco lists).
//
// STAND-INS shared by all four (named per-driver too):
//  - uStrike / the PCM strike: the app's PcmSink taps raw session PCM; the
//    harness feeds the PcmPulse mirror the audio MODEL's 128-sample waveform.
//    Same envelope machine, plausible but not real amplitudes.
//  - ACID's uChroma: the audio models synthesize a triad chromagram (see
//    lib/audio.mjs); the app sends zeros only when no chromagram ran.

/** Shared scene clock wrap (each of the four scenes' TIME_WRAP_SECONDS). */
const FIELD_TIME_WRAP = 628.31853;

/** CymaticsMath.safeDrive: finite, clamped to 0..MAX_DRIVE (4). */
function safeDrive(raw) {
  return Number.isFinite(raw) ? clamp(raw, 0, 4) : 0;
}

/** PcmPulse.kt, verbatim: peak-hold with a 4/s decay, ceiling 1.5. */
class PcmPulse {
  constructor(decayPerSecond = 4, ceiling = 1.5) {
    this.decayPerSecond = decayPerSecond;
    this.ceiling = ceiling;
    this.level = 0;
  }

  accept(samples) {
    let peak = 0;
    for (let i = 0; i < samples.length; i++) {
      const s = samples[i];
      if (Number.isFinite(s)) {
        const a = Math.abs(s);
        if (a > peak) peak = a;
      }
    }
    if (peak > this.level) this.level = Math.min(peak, this.ceiling);
  }

  tick(dt) {
    const out = this.level;
    this.level = Math.max(this.level - dt * this.decayPerSecond, 0);
    return out;
  }
}

/** The scenes' shared slew shape (each declares its own rise/fall rates). */
function slewEnv(current, target, dt, risePerSec, fallPerSec) {
  const limit = target > current ? risePerSec : fallPerSec;
  return current + clamp(target - current, -limit * dt, limit * dt);
}

/**
 * SceneParams.DEFAULT, the subset these four scenes read. palette 0 is
 * "Spectrum" (base 0.0, range 1.0) - see SceneParams.PALETTES.
 */
const FIELD_PARAM_DEFAULTS = {
  speed: 1, audioDrive: 1, beatResponse: 1, turbulence: 0,
  trails: false, trailLength: 0.5,
  paletteBase: 0, paletteRange: 1, hueRange: 1,
};

// ---------------------------------------------------------------------------
// Style tables - verbatim from render/scene/VisualStyleCatalog.kt.
// Do NOT edit numbers here without editing the catalog; the file is cited so
// the drift is findable. Defaults are each data class's defaults.
// ---------------------------------------------------------------------------

/** VisualStyleCatalog.SilkStyle defaults. */
const SILK_DEFAULTS = {
  flow: 1, fieldScale: 1, strokes: 1, elong: 1, decay: 0.985, fold: 0,
  swirl: 0.25, exposure: 1.35, hueOffset: 0, hueSpan: 1,
  bBase: 0.17, bAmp: 0.05, bPeriod: 37, slabRate: 0.02,
};

/** VisualStyleCatalog.silk. */
export const SILK_STYLES = [
  { id: 'silk_web', label: 'Halvorsen Web', field: 0 },
  {
    id: 'silk_bloom', label: 'Cosine Bloom', field: 1,
    flow: 0.85, fieldScale: 0.8, decay: 0.988, swirl: 0.15,
    hueOffset: 0.55, bBase: 0.16, bAmp: 0.05, bPeriod: 41,
  },
  {
    id: 'silk_weave', label: 'Triaxial Weave', field: 2,
    flow: 1.15, fieldScale: 1.25, strokes: 1.3, elong: 0.7,
    decay: 0.975, hueOffset: 0.12, bBase: 0.16, bAmp: 0.045, bPeriod: 47,
  },
  {
    id: 'silk_shell', label: 'Concentric Shells', field: 3,
    flow: 0.9, fieldScale: 0.9, strokes: 0.8, elong: 1.4,
    decay: 0.987, swirl: 0.4, hueOffset: -0.18,
    bBase: 0.18, bAmp: 0.035, bPeriod: 38,
  },
  {
    id: 'silk_spiral', label: 'Phase Spiral', field: 4,
    flow: 1.2, fieldScale: 1.05, elong: 1.8, decay: 0.982,
    swirl: 0.5, hueOffset: 0.3, bBase: 0.17, bAmp: 0.045, bPeriod: 49,
  },
  {
    id: 'silk_fold', label: 'Recursive Fold', field: 5,
    fieldScale: 1.15, strokes: 1.2, decay: 0.98,
    hueOffset: 0.78, bBase: 0.2, bAmp: 0.04, bPeriod: 44,
  },
  {
    id: 'silk_hyper', label: 'Hyperbolic Bloom', field: 6,
    flow: 0.8, fieldScale: 0.75, elong: 2.2, decay: 0.99,
    hueOffset: 0.48, bBase: 0.22, bAmp: 0.05, bPeriod: 35,
  },
  {
    id: 'silk_resonance', label: 'Nested Resonance', field: 7,
    flow: 1.05, swirl: 0.3, hueOffset: 0.06,
    bBase: 0.19, bAmp: 0.04, bPeriod: 43,
  },
  {
    id: 'silk_curl', label: 'Curl Weave', field: 8,
    flow: 1.3, fieldScale: 1.2, strokes: 1.4, elong: 0.9,
    decay: 0.978, hueOffset: 0.62,
  },
  {
    id: 'silk_pendulum', label: 'Pendulum Garden', field: 9,
    flow: 0.95, fieldScale: 0.85, elong: 1.5, decay: 0.986,
    fold: 3, swirl: 0, hueOffset: 0.9,
  },
].map((s) => ({ ...SILK_DEFAULTS, ...s }));

/** VisualStyleCatalog.LifeStyle defaults. */
const LIFE_DEFAULTS = {
  core: 0, growth: 0, mu: 0.15, sigma: 0.017, radius: 13, rings: 1,
  b1: 1, b2: 0, b3: 0, feed: 0, kill: 0, aniso: 0, substeps: 1, look: 0,
  seedJitter: 9, hueOffset: 0, hueSpan: 1,
};

/** VisualStyleCatalog.life. */
export const LIFE_STYLES = [
  { id: 'life_orbium', label: 'Orbium Drift', rule: 0, dt: 0.1, mu: 0.15, sigma: 0.017, look: 0 },
  { id: 'life_gyre', label: 'Gyre Garden', rule: 0, dt: 0.1, mu: 0.156, sigma: 0.0224, look: 3, hueOffset: 0.5 },
  {
    id: 'life_helix', label: 'Helicium Reef', rule: 0, dt: 0.1, mu: 0.3, sigma: 0.0505,
    look: 2, hueOffset: 0.12, seedJitter: 7,
  },
  {
    id: 'life_pulsar', label: 'Pulsar Colony', rule: 0, dt: 0.1, mu: 0.38, sigma: 0.07,
    look: 0, hueOffset: 0.07, seedJitter: 6,
  },
  {
    id: 'life_hydro', label: 'Hydrogeminium', rule: 0, dt: 0.1, core: 1, growth: 1,
    mu: 0.26, sigma: 0.036, radius: 18, rings: 3, b2: 1, b3: 1,
    look: 2, hueOffset: 0.4, seedJitter: 5,
  },
  {
    id: 'life_bug', label: 'Smooth Bugs', rule: 0, dt: 1, core: 2, mu: 0.31, sigma: 0.049,
    look: 4, hueOffset: 0.85, seedJitter: 8,
  },
  {
    id: 'life_mitosis', label: 'Mitosis', rule: 1, dt: 1, feed: 0.0367, kill: 0.0649,
    substeps: 4, look: 0, hueOffset: 0.6, seedJitter: 11,
  },
  {
    id: 'life_coral', label: 'Coral Bloom', rule: 1, dt: 1, feed: 0.0545, kill: 0.062,
    substeps: 5, look: 2, hueOffset: 0.02, seedJitter: 8,
  },
  {
    id: 'life_labyrinth', label: 'Living Ink', rule: 1, dt: 1, feed: 0.026, kill: 0.055,
    substeps: 5, look: 1, hueOffset: 0.09, seedJitter: 7,
  },
  {
    id: 'life_worms', label: 'Ember Worms', rule: 1, dt: 1, feed: 0.078, kill: 0.061,
    substeps: 4, look: 5, seedJitter: 10,
  },
].map((s) => ({ ...LIFE_DEFAULTS, ...s }));

/** VisualStyleCatalog.AcidStyle defaults. */
const ACID_DEFAULTS = {
  zoom: 1.010, rotate: 0.0015, hueRate: 0.04, feedback: 0.955, modulate: 0.35,
  glitch: 0, overdrive: 0, liquid: 0, scanline: 0, curve: 0, saturation: 1.05,
  hueOffset: 0, hueSpan: 1,
};

/** VisualStyleCatalog.acid. */
export const ACID_STYLES = [
  {
    id: 'acid_tv', label: 'TV Acid', mode: 0, source: 0,
    overdrive: 0.8, liquid: 0.8, glitch: 0.3, scanline: 0.25,
    curve: 0.3, saturation: 1.15,
  },
  {
    id: 'acid_well', label: 'Phosphor Well', mode: 1, source: 1,
    zoom: 1.035, rotate: 0.001, hueRate: 0.015, feedback: 0.965,
    modulate: 0.2, scanline: 0.45, curve: 0.5, saturation: 0.8,
    hueOffset: 0.33,
  },
  {
    id: 'acid_kaleid', label: 'Kaleido Melt', mode: 2, source: 0,
    zoom: 1.014, rotate: 0.002, hueRate: 0.05, feedback: 0.95,
    modulate: 0.5, liquid: 0.5, saturation: 1.1,
  },
  {
    id: 'acid_droste', label: 'Droste Throat', mode: 3, source: 1,
    zoom: 1.0, rotate: 0.0012, hueRate: 0.03, feedback: 0.96,
    modulate: 0.3, hueOffset: 0.72,
  },
  {
    id: 'acid_prism', label: 'Prism Drift', mode: 4, source: 1,
    zoom: 1.008, rotate: -0.0014, hueRate: 0.02, feedback: 0.96,
    hueOffset: 0.45,
  },
  {
    id: 'acid_mosh', label: 'Datamosh', mode: 5, source: 2,
    zoom: 1.004, rotate: 0, hueRate: 0.06, feedback: 0.945,
    glitch: 1, overdrive: 0.5, hueOffset: 0.18,
  },
  {
    id: 'acid_scan', label: 'Scanline Surge', mode: 6, source: 2,
    zoom: 1.006, rotate: 0, hueRate: 0.03, feedback: 0.95,
    glitch: 0.7, scanline: 0.8, curve: 0.6, hueOffset: 0.55,
  },
  {
    id: 'acid_solar', label: 'Solar Flare', mode: 7, source: 1,
    zoom: 1.012, rotate: 0.0018, hueRate: 0.08, feedback: 0.93,
    overdrive: 0.3, hueOffset: 0.06,
  },
  {
    id: 'acid_mirror', label: 'Mirror Room', mode: 8, source: 0,
    zoom: 1.009, rotate: 0.0008, hueRate: 0.035, feedback: 0.958,
    modulate: 0.45, hueOffset: 0.85,
  },
  {
    id: 'acid_smear', label: 'Neon Smear', mode: 9, source: 3,
    zoom: 1.005, rotate: 0.0005, hueRate: 0.045, feedback: 0.962,
    overdrive: 0.6, liquid: 0.3, hueOffset: 0.6,
  },
].map((s) => ({ ...ACID_DEFAULTS, ...s }));

/** VisualStyleCatalog.MycoStyle defaults. */
const MYCO_DEFAULTS = {
  agentRes: 192, sensorDist: 9, sensorAngle: 0.3927, turnAngle: 0.7854,
  moveStep: 1, jitter: 0.06, deposit: 0.12, decay: 0.905, speciesMix: 0,
  selfA: 1, crossAb: 0, crossBa: 0, selfB: 1, snap: 0, reaim: 0, aniso: 0,
  look: 0, exposure: 3.4, hueOffset: 0, hueSpan: 1,
};

/** VisualStyleCatalog.myco. */
export const MYCO_STYLES = [
  { id: 'myco_polycephalum', label: 'Polycephalum', reaim: 0.15 },
  {
    id: 'myco_rivals', label: 'Rival Colonies',
    sensorDist: 11, speciesMix: 0.5, crossAb: -1, crossBa: -1,
    look: 1, exposure: 2.6, hueSpan: 1.2, reaim: 0.2,
  },
  {
    id: 'myco_symbiosis', label: 'Symbiosis',
    sensorDist: 8, sensorAngle: 0.45, speciesMix: 0.5,
    crossAb: 0.35, crossBa: 0.35, hueOffset: 0.1, reaim: 0.15,
  },
  {
    id: 'myco_predator', label: 'Predator',
    agentRes: 176, sensorDist: 12, moveStep: 1.25, speciesMix: 0.35,
    selfA: 0.35, crossAb: 1.6, crossBa: -1.3,
    look: 4, hueOffset: 0.03, reaim: 0.25,
  },
  {
    id: 'myco_ghosts', label: 'Ghost Veil',
    agentRes: 160, sensorDist: 14, jitter: 0.12, deposit: 0.05,
    decay: 0.955, look: 2, exposure: 4.2, hueOffset: 0.58,
  },
  {
    id: 'myco_circuit', label: 'Circuit Bloom',
    agentRes: 176, sensorAngle: 0.6, jitter: 0, deposit: 0.12,
    decay: 0.87, snap: 0.7854, look: 3, exposure: 3.2, hueOffset: 0.35,
  },
  {
    id: 'myco_silkroad', label: 'Silk Roads',
    agentRes: 176, sensorDist: 22, sensorAngle: 0.18, turnAngle: 0.12,
    moveStep: 1.35, jitter: 0.02, deposit: 0.10, decay: 0.93,
    look: 1, hueOffset: -0.2,
  },
  {
    id: 'myco_sporestorm', label: 'Spore Storm',
    sensorDist: 8, deposit: 0.16, decay: 0.87, reaim: 0.5, hueOffset: 0.68,
  },
  {
    id: 'myco_capillary', label: 'Capillaries',
    agentRes: 208, sensorDist: 5, sensorAngle: 0.85, turnAngle: 1.1,
    moveStep: 0.75, deposit: 0.14, decay: 0.9,
    look: 5, exposure: 3.8, hueOffset: 0.98,
  },
  {
    id: 'myco_frostvein', label: 'Frost Veins',
    agentRes: 176, sensorDist: 10, sensorAngle: 0.35, turnAngle: 0.5,
    moveStep: 0.9, deposit: 0.12, decay: 0.915, aniso: 0.8,
    look: 1, hueOffset: 0.52,
  },
].map((s) => ({ ...MYCO_DEFAULTS, ...s }));

// ---------------------------------------------------------------------------
// SILK - render/scene/SilkScene.kt
// ---------------------------------------------------------------------------
//
// One ping-pong dye texture (three band lanes), stepped by silk_step_frag and
// presented by silk_show_frag. Pass structure per frame, as draw() sequences
// it: step (advect + inject, blend off) into the write side, swap, then show
// to the renderer's target.

export function createSilkDriver({ style, params, width, height }) {
  const p = { ...FIELD_PARAM_DEFAULTS, ...params };
  /** SilkScene.SIM_RES = 320: "the dye is soft by nature; 320 is plenty". */
  const [simW, simH] = fluidResolution(320, width, height);
  const TAU = Math.PI * 2;

  const pulse = new PcmPulse();
  let time = 0;
  let envBass = 0;
  let envMid = 0;
  let envTreble = 0;
  let beatPulse = 0;
  let ringRadius = -1;
  let slabTurn = 0;
  let foldPhase = 0;
  let drift = 0;

  const supplies = new Set([
    // step
    'uPrev', 'uRes', 'uField', 'uB', 'uAdvect', 'uDecay', 'uFieldScale', 'uSwirl',
    'uSlabX', 'uSlabY', 'uSeedEpoch', 'uDrift', 'uStrokes', 'uElong', 'uDrive',
    'uBass', 'uMid', 'uTreble', 'uBeat', 'uStrike', 'uBeatRing', 'uStateScale',
    // show (uField/uRes recur with different meanings; the audit is by name)
    'uBaseHue', 'uHueSpan', 'uExposure', 'uFold', 'uFoldPhase', 'uEnergy',
  ]);

  function step(f, dt) {
    // SilkScene.update()
    time = (time + dt) % FIELD_TIME_WRAP;
    pulse.accept(f.waveform);
    const pcmStrike = pulse.tick(dt);

    // SilkScene.draw()
    const d = clamp(dt, 0, 1 / 15);
    const speed = clamp(p.speed, 0.05, 4);
    // ENV_RISE_PER_SEC = 8, ENV_FALL_PER_SEC = 2.2
    envBass = slewEnv(envBass, clamp(f.bass, 0, 1.5), d, 8, 2.2);
    envMid = slewEnv(envMid, clamp(f.mid, 0, 1.5), d, 8, 2.2);
    envTreble = slewEnv(envTreble, clamp(f.treble, 0, 1.5), d, 8, 2.2);
    beatPulse = clamp(
      Math.max(motionImpulse(f) * clamp(p.beatResponse, 0, 2), beatPulse - d * 3), 0, 1.5,
    );
    // BEAT_THRESHOLD = 0.28; beatResponse deliberately unclamped here, as in
    // the Kotlin.
    if (beatImpulseOf(f) * p.beatResponse > 0.28) ringRadius = 0;
    if (ringRadius >= 0) {
      // RING_SPEED = 2.6, RING_MAX = 3.4
      ringRadius += d * 2.6 * speed;
      if (ringRadius > 3.4) ringRadius = -1;
    }

    slabTurn = (slabTurn + d * style.slabRate * speed) % 1;
    foldPhase = (foldPhase + d * 0.03 * speed * TAU) % TAU;
    drift = (drift + d * 0.05 * speed) % 1024;
    const b = style.bBase + style.bAmp * Math.sin(TAU * time / style.bPeriod);
    // SEED_EPOCH_SECONDS = 9; Kotlin (time / 9f).toInt() truncates.
    const seedEpoch = Math.trunc(time / 9);

    // Feedback survival, frame-rate compensated; Trails lengthens it.
    let decay = style.decay;
    if (p.trails) decay += (1 - decay) * 0.6 * clamp(p.trailLength, 0, 1);
    const frameDecay = Math.pow(decay, d * 60);

    const stepU = {
      uPrev: { t: 'tex', v: 0 },
      uRes: { t: '2f', v: [simW, simH] },
      uField: { t: '1i', v: style.field },
      uB: { t: '1f', v: b },
      uAdvect: { t: '1f', v: d * 0.18 * style.flow * speed },
      uDecay: { t: '1f', v: frameDecay },
      uFieldScale: { t: '1f', v: style.fieldScale },
      uSwirl: { t: '1f', v: style.swirl },
      uSlabX: { t: '1f', v: Math.cos(slabTurn * TAU) },
      uSlabY: { t: '1f', v: Math.sin(slabTurn * TAU) },
      uSeedEpoch: { t: '1f', v: seedEpoch },
      uDrift: { t: '1f', v: drift },
      uStrokes: { t: '1f', v: style.strokes },
      uElong: { t: '1f', v: style.elong },
      uDrive: { t: '1f', v: safeDrive(p.audioDrive) },
      uBass: { t: '1f', v: envBass },
      uMid: { t: '1f', v: envMid },
      uTreble: { t: '1f', v: envTreble },
      uBeat: { t: '1f', v: beatPulse },
      uStrike: { t: '1f', v: clamp(pcmStrike, 0, 1.5) },
      uBeatRing: { t: '1f', v: ringRadius },
      // The app sends BYTE_STATE_SCALE = 8 only on its RGBA8 fallback path;
      // the harness always renders the float target, so the scale is 1.
      uStateScale: { t: '1f', v: 1 },
    };
    const showU = {
      uField: { t: 'tex', v: 0 },
      uRes: { t: '2f', v: [width, height] },
      uBaseHue: { t: '1f', v: H.FluidHue.base(p.paletteBase) + style.hueOffset },
      uHueSpan: { t: '1f', v: H.FluidHue.span(p.hueRange, p.paletteRange) * style.hueSpan },
      uExposure: { t: '1f', v: style.exposure },
      uFold: { t: '1i', v: style.fold },
      uFoldPhase: { t: '1f', v: foldPhase },
      uEnergy: { t: '1f', v: clamp(f.rms, 0, 1.5) },
      uStateScale: { t: '1f', v: 1 },
    };

    return {
      uniforms: null,
      melt: null,
      passes: [
        {
          program: 'step', target: 'dye', draw: 'triangle', blend: null, swap: true,
          textures: [{ unit: 0, target: 'dye', side: 'read' }],
          uniforms: stepU,
        },
        {
          program: 'show', target: 'screen', draw: 'triangle', blend: null,
          textures: [{ unit: 0, target: 'dye', side: 'read' }],
          uniforms: showU,
        },
      ],
      debug: { time: round4(time), b: round4(b), ringRadius: round4(ringRadius), beatPulse: round4(beatPulse) },
    };
  }

  /** Free-running clocks only; the dye does not age. */
  function jumpClock(seconds) {
    time = (time + seconds) % FIELD_TIME_WRAP;
  }

  return {
    id: 'silk', supplies, step, jumpClock,
    meltConfig: { enabled: false },
    fieldPrograms: {
      step: ['quad_vert', 'silk_step_frag'],
      show: ['quad_vert', 'silk_show_frag'],
    },
    // FluidBuffers.DoubleFbo(w, h, fmt.rgba, linear = true): RGBA16F where
    // renderable (probed page-side), linear filtering.
    fieldTargets: { dye: { width: simW, height: simH, format: 'rgba16f', filter: 'linear' } },
    standIns: [
      'uStrike is a stand-in: PcmPulse fed the audio model\'s waveform, not the app\'s raw PCM tap',
    ],
  };
}

// ---------------------------------------------------------------------------
// LIFE - render/scene/LifeScene.kt
// ---------------------------------------------------------------------------
//
// One ping-pong state texture stepped by life_step_frag (Lenia or Gray-Scott)
// and presented by life_show_frag. Pass structure per frame: N substeps of
// step (injections uSeed/uKick/uSprinkle land ONLY on the first substep,
// exactly as draw() gates them), then show. The liveness census - the app
// reads one state texel every 4 s and reseeds a starved or overgrown world -
// is mirrored through the harness's probe channel: the page returns the
// centre texel of the state's read side at the end of each frame, and that
// value IS what the app's census would read at the start of the next frame
// (nothing writes the state in between), so the timing is exact.

export function createLifeDriver({ style, params, width, height }) {
  const p = { ...FIELD_PARAM_DEFAULTS, ...params };
  /** LifeScene.SIM_RES = 288: "room for many R=13..20 organisms". */
  const [simW, simH] = fluidResolution(288, width, height);
  /** GOLDEN_ANGLE = 2.399963, SEED_SECONDS = 0.5, CENSUS_SECONDS = 4. */
  const GOLDEN_ANGLE = 2.399963;
  const SEED_SECONDS = 0.5;

  const pulse = new PcmPulse();
  let time = 0;
  let envTreble = 0;
  let beatPulse = 0;
  let seedRemain = SEED_SECONDS; // set by init() before the first draw
  let kick = 0;
  let kickAngle = 0;
  let kickX = 0.5;
  let kickY = 0.5;
  let censusAge = 0;
  let lastProbe = null;

  const supplies = new Set([
    // step
    'uRes', 'uRule', 'uDt', 'uCore', 'uGrowth', 'uMu', 'uSigma', 'uRadius', 'uRings',
    'uB', 'uF', 'uK', 'uDiff', 'uAniso', 'uSeedJitter', 'uTime',
    'uPrev', 'uSeed', 'uKick', 'uKickPos', 'uSprinkle',
    // show
    'uState', 'uSimRes', 'uLook', 'uShowV', 'uBaseHue', 'uHueSpan', 'uEnergy', 'uBeat',
  ]);

  /**
   * LifeScene.census(), fed by the page's probe of the state's centre texel.
   * The app reads GL_FLOAT at a five-probe cross (quarter points + centre)
   * and calls the world starved only when the LIVEST probe is dead, overgrown
   * only when the DIMMEST is saturated. The harness's probe channel carries
   * one texel - the centre, the app's probe [2,2] - so this mirror is the
   * app's census degenerated to that probe (max = min = centre); see standIns.
   */
  function census(probe) {
    const a = probe[0];
    const v = probe[1];
    const live = style.rule === 0 ? a : v;
    // STARVED = 0.004, OVERGROWN = 0.985
    const starving = live < 0.004 && (style.rule === 1 ? a > 0.9 : true);
    if (starving || live > 0.985) seedRemain = SEED_SECONDS;
  }

  function step(f, dt) {
    // LifeScene.update()
    time = (time + dt) % FIELD_TIME_WRAP;
    pulse.accept(f.waveform);
    const pcmStrike = pulse.tick(dt);

    // LifeScene.draw()
    const d = clamp(dt, 0, 1 / 15);
    const speed = clamp(p.speed, 0.05, 4);
    const drive = safeDrive(p.audioDrive);
    // ENV_RISE_PER_SEC = 9, ENV_FALL_PER_SEC = 2.4
    envTreble = slewEnv(envTreble, clamp(f.treble, 0, 1.5), d, 9, 2.4);
    beatPulse = clamp(
      Math.max(motionImpulse(f) * clamp(p.beatResponse, 0, 2), beatPulse - d * 3), 0, 1.5,
    );
    kick = Math.max(kick - d * 5, 0);
    // BEAT_THRESHOLD = 0.3
    if (beatImpulseOf(f) * p.beatResponse > 0.3) {
      kick = (0.4 + 0.6 * clamp(beatImpulseOf(f), 0, 1.5)) * drive;
      kickAngle += GOLDEN_ANGLE;
      kickX = 0.5 + 0.32 * Math.cos(kickAngle);
      kickY = 0.5 + 0.32 * Math.sin(kickAngle);
    }
    seedRemain = Math.max(seedRemain - d, 0);
    censusAge += d;
    if (censusAge >= 4) {
      censusAge = 0;
      if (lastProbe) census(lastProbe);
    }

    const substeps = clamp(Math.round(style.substeps * speed), 1, 8);

    const common = {
      uRes: { t: '2f', v: [simW, simH] },
      uRule: { t: '1i', v: style.rule },
      uDt: { t: '1f', v: style.dt },
      uCore: { t: '1i', v: style.core },
      uGrowth: { t: '1i', v: style.growth },
      uMu: { t: '1f', v: style.mu },
      uSigma: { t: '1f', v: style.sigma },
      uRadius: { t: '1f', v: style.radius },
      uRings: { t: '1i', v: style.rings },
      uB: { t: '3f', v: [style.b1, style.b2, style.b3] },
      uF: { t: '1f', v: style.feed },
      uK: { t: '1f', v: style.kill },
      uDiff: { t: '2f', v: [1.0, 0.5] },
      uAniso: { t: '1f', v: style.aniso },
      uSeedJitter: { t: '1f', v: style.seedJitter },
      uTime: { t: '1f', v: time },
      uPrev: { t: 'tex', v: 0 },
      uKickPos: { t: '2f', v: [kickX, kickY] },
    };

    const passes = [];
    for (let pass = 0; pass < substeps; pass++) {
      // Injections land once per frame, not once per substep.
      const first = pass === 0;
      passes.push({
        program: 'step', target: 'state', draw: 'triangle', blend: null, swap: true,
        textures: [{ unit: 0, target: 'state', side: 'read' }],
        uniforms: {
          ...common,
          uSeed: { t: '1f', v: first ? seedRemain / SEED_SECONDS : 0 },
          uKick: { t: '1f', v: first ? kick : 0 },
          uSprinkle: { t: '1f', v: first ? (envTreble + pcmStrike * 0.5) * drive : 0 },
        },
      });
    }
    kick = 0;

    passes.push({
      program: 'show', target: 'screen', draw: 'triangle', blend: null,
      textures: [{ unit: 0, target: 'state', side: 'read' }],
      uniforms: {
        uState: { t: 'tex', v: 0 },
        uRes: { t: '2f', v: [width, height] },
        uSimRes: { t: '2f', v: [simW, simH] },
        uLook: { t: '1i', v: style.look },
        uShowV: { t: '1f', v: style.rule === 1 ? 1 : 0 },
        uBaseHue: { t: '1f', v: H.FluidHue.base(p.paletteBase) + style.hueOffset },
        uHueSpan: { t: '1f', v: H.FluidHue.span(p.hueRange, p.paletteRange) * style.hueSpan },
        uEnergy: { t: '1f', v: clamp(f.rms, 0, 1.5) },
        uBeat: { t: '1f', v: beatPulse },
      },
    });

    return {
      uniforms: null,
      melt: null,
      passes,
      probe: { target: 'state' },
      debug: {
        time: round4(time), substeps, seed: round4(seedRemain / SEED_SECONDS),
        kick: round4(kick), beatPulse: round4(beatPulse),
      },
    };
  }

  /** The page's centre-texel readback, applied to the NEXT census. */
  function feedback(probe) {
    lastProbe = probe;
  }

  function jumpClock(seconds) {
    time = (time + seconds) % FIELD_TIME_WRAP;
  }

  return {
    id: 'life', supplies, step, feedback, jumpClock,
    meltConfig: { enabled: false },
    fieldPrograms: {
      step: ['quad_vert', 'life_step_frag'],
      show: ['quad_vert', 'life_show_frag'],
    },
    fieldTargets: { state: { width: simW, height: simH, format: 'rgba16f', filter: 'linear' } },
    standIns: [
      'uSprinkle\'s PCM strike is a stand-in: PcmPulse fed the audio model\'s waveform',
      'the 4-second liveness census reads only the centre texel (the app\'s probe [2,2]); '
      + 'the app reads a five-probe cross, so the harness reseeds a sparse-but-alive '
      + 'world the app would spare',
    ],
  };
}

// ---------------------------------------------------------------------------
// ACID - render/scene/AcidScene.kt
// ---------------------------------------------------------------------------
//
// One RGBA8 ping-pong feedback loop stepped by acid_step_frag (warp + hue
// rotate + attenuate + audio source layer) and presented by acid_show_frag
// (CRT dressing). Pass structure per frame: step into the write side, swap,
// show. All loop constants are per-frame quantities at the authored 60 Hz;
// the scene compensates frame rate and hard-caps feedback below 1.

export function createAcidDriver({ style, params, width, height }) {
  const p = { ...FIELD_PARAM_DEFAULTS, ...params };
  /** AcidScene.SIM_RES = 540: "echoes soften anyway, full res is waste". */
  const [simW, simH] = fluidResolution(540, width, height);
  /** FEEDBACK_CAP = 0.975, GLITCH_THRESHOLD = 0.32, GLITCH_DECAY = 2.4. */
  const FEEDBACK_CAP = 0.975;

  const pulse = new PcmPulse();
  let time = 0;
  let envBass = 0;
  let envMid = 0;
  let envTreble = 0;
  let beatPulse = 0;
  let glitch = 0;
  let glitchEpoch = 0;
  const chroma = new Array(12).fill(0);

  const supplies = new Set([
    // step
    'uPrev', 'uRes', 'uStyle', 'uSource', 'uZoom', 'uRotate', 'uHueShift', 'uFeedback',
    'uModulate', 'uGlitch', 'uEpoch', 'uTime', 'uBass', 'uMid', 'uTreble', 'uBeat',
    'uStrike', 'uDrive', 'uChroma', 'uBaseHue', 'uHueSpan', 'uOverdrive', 'uLiquid',
    // show
    'uState', 'uScanline', 'uCurve', 'uSat', 'uFloorHue', 'uHit',
  ]);

  function step(f, dt) {
    // AcidScene.update()
    time = (time + dt) % FIELD_TIME_WRAP;
    pulse.accept(f.waveform);
    const pcmStrike = pulse.tick(dt);

    // AcidScene.draw()
    const d = clamp(dt, 0, 1 / 15);
    const speed = clamp(p.speed, 0.05, 4);
    // ENV_RISE_PER_SEC = 9, ENV_FALL_PER_SEC = 2.4
    envBass = slewEnv(envBass, clamp(f.bass, 0, 1.5), d, 9, 2.4);
    envMid = slewEnv(envMid, clamp(f.mid, 0, 1.5), d, 9, 2.4);
    envTreble = slewEnv(envTreble, clamp(f.treble, 0, 1.5), d, 9, 2.4);
    beatPulse = clamp(
      Math.max(motionImpulse(f) * clamp(p.beatResponse, 0, 2), beatPulse - d * 3), 0, 1.5,
    );
    if (beatImpulseOf(f) * p.beatResponse > 0.32) {
      glitch = 1;
      glitchEpoch = (glitchEpoch + 1) % 1024;
    }
    glitch = Math.max(glitch - d * 2.4, 0);
    const hasChroma = f.chroma && f.chroma.length === 12 && (f.chromaConfidence ?? 1) > 0.1;
    if (hasChroma) {
      for (let i = 0; i < 12; i++) chroma[i] = clamp(f.chroma[i], 0, 1);
    } else {
      // AcidScene.draw's synthetic three-spoke mandala for unpitched material.
      const spin = (time * 0.05) % 1;
      for (let i = 0; i < 12; i++) {
        const angle = (i / 12 - spin + 2) % 1;
        const spoke = 1 - Math.abs(angle - 0.5) * 2;
        const band = i % 3 === 0 ? envBass : (i % 3 === 1 ? envMid : envTreble);
        chroma[i] = clamp(spoke * spoke * band, 0, 1);
      }
    }

    // Frame-rate-compensated loop constants: survival, zoom and rotation are
    // per-frame quantities at the authored 60 Hz.
    const frames = d * 60;
    const feedback = Math.pow(Math.min(style.feedback, FEEDBACK_CAP), frames);
    const zoom = Math.pow(style.zoom, frames);
    const rotate = style.rotate * frames * speed;
    const hueShift = style.hueRate * d * speed;

    const stepU = {
      uPrev: { t: 'tex', v: 0 },
      uRes: { t: '2f', v: [simW, simH] },
      uStyle: { t: '1i', v: style.mode },
      uSource: { t: '1i', v: style.source },
      uZoom: { t: '1f', v: zoom },
      uRotate: { t: '1f', v: rotate },
      uHueShift: { t: '1f', v: hueShift },
      uFeedback: { t: '1f', v: feedback },
      uModulate: { t: '1f', v: style.modulate },
      uGlitch: { t: '1f', v: glitch * style.glitch },
      uEpoch: { t: '1f', v: glitchEpoch },
      uTime: { t: '1f', v: time },
      uBass: { t: '1f', v: envBass },
      uMid: { t: '1f', v: envMid },
      uTreble: { t: '1f', v: envTreble },
      uBeat: { t: '1f', v: beatPulse },
      uStrike: { t: '1f', v: clamp(pcmStrike, 0, 1.5) },
      uDrive: { t: '1f', v: safeDrive(p.audioDrive) },
      uChroma: { t: '1fv', v: chroma.slice() },
      uBaseHue: { t: '1f', v: H.FluidHue.base(p.paletteBase) + style.hueOffset },
      uHueSpan: { t: '1f', v: H.FluidHue.span(p.hueRange, p.paletteRange) * style.hueSpan },
      uLiquid: { t: '1f', v: style.liquid + clamp(p.turbulence, 0, 1) * 0.6 },
    };
    const showU = {
      uState: { t: 'tex', v: 0 },
      uRes: { t: '2f', v: [width, height] },
      uScanline: { t: '1f', v: style.scanline },
      uCurve: { t: '1f', v: style.curve },
      uSat: { t: '1f', v: style.saturation },
      uFloorHue: { t: '1f', v: H.FluidHue.base(p.paletteBase) + style.hueOffset },
      uOverdrive: { t: '1f', v: style.overdrive },
      uHit: { t: '1f', v: clamp(pcmStrike + 0.5 * beatPulse, 0, 1) },
    };

    return {
      uniforms: null,
      melt: null,
      passes: [
        {
          program: 'step', target: 'loop', draw: 'triangle', blend: null, swap: true,
          textures: [{ unit: 0, target: 'loop', side: 'read' }],
          uniforms: stepU,
        },
        {
          program: 'show', target: 'screen', draw: 'triangle', blend: null,
          textures: [{ unit: 0, target: 'loop', side: 'read' }],
          uniforms: showU,
        },
      ],
      debug: { time: round4(time), glitch: round4(glitch), beatPulse: round4(beatPulse) },
    };
  }

  function jumpClock(seconds) {
    time = (time + seconds) % FIELD_TIME_WRAP;
  }

  return {
    id: 'acid', supplies, step, jumpClock,
    meltConfig: { enabled: false },
    fieldPrograms: {
      step: ['quad_vert', 'acid_step_frag'],
      show: ['quad_vert', 'acid_show_frag'],
    },
    // RGBA8 by design: "an echo loop tolerates 8 bits, and the fallback
    // question float targets pose does not arise at all".
    fieldTargets: { loop: { width: simW, height: simH, format: 'rgba8', filter: 'linear' } },
    standIns: [
      'uStrike is a stand-in: PcmPulse fed the audio model\'s waveform',
      'uChroma comes from the audio model\'s synthetic triad chromagram; the app sends '
      + 'zeros only when no chromagram ran (which would leave source-0 styles unfed)',
    ],
  };
}

// ---------------------------------------------------------------------------
// MYCO - render/scene/MycoScene.kt
// ---------------------------------------------------------------------------
//
// Two ping-pongs: the agent texture (one agent per texel, RGBA32F where
// renderable, NEAREST) and the pheromone trail (RG16F, LINEAR). Pass
// structure per frame, exactly as draw() sequences it:
//   1. myco_agent_frag steps every agent (uInit=1 seeds the population on the
//      very first frame), swap;
//   2. myco_deposit_* lands one additive GL_POINT per agent INTO THE TRAIL'S
//      READ SIDE - no swap: the deposit accumulates on the same texture the
//      blur will read;
//   3. myco_blur_frag diffuses + decays read -> write, swap;
//   4. myco_show_frag presents the field.

export function createMycoDriver({ style, params, width, height }) {
  const p = { ...FIELD_PARAM_DEFAULTS, ...params };
  /** MycoScene.TRAIL_RES = 384. */
  const [trailW, trailH] = fluidResolution(384, width, height);
  const agentRes = style.agentRes;
  /** BYTE_FALLBACK_DEPOSIT = 0.125: deposit rescale on the RGBA8 fallback. */
  const BYTE_FALLBACK_DEPOSIT = 0.125;

  const pulse = new PcmPulse();
  let time = 0;
  let envBass = 0;
  let envTreble = 0;
  let beatPulse = 0;
  let agentsSeeded = false;
  let byteTrail = false;

  const supplies = new Set([
    // agent
    'uAgents', 'uTrail', 'uAgentRes', 'uTrailRes', 'uInit', 'uSpeciesMix',
    'uSensorDist', 'uSensorAngle', 'uTurnAngle', 'uMoveStep', 'uMatrix', 'uBreath',
    'uJitter', 'uSnap', 'uReaim', 'uTime', 'uAniso',
    // deposit
    'uDeposit',
    // blur
    'uDecay',
    // show
    'uRes', 'uLook', 'uBaseHue', 'uHueSpan', 'uExposure', 'uEnergy', 'uBeat',
  ]);

  function step(f, dt) {
    // MycoScene.update()
    time = (time + dt) % FIELD_TIME_WRAP;
    pulse.accept(f.waveform);
    pulse.tick(dt); // pcmStrike is tracked but unread by this scene's draw()

    // MycoScene.draw()
    const d = clamp(dt, 0, 1 / 15);
    const speed = clamp(p.speed, 0.05, 4);
    const drive = safeDrive(p.audioDrive);
    // ENV_RISE_PER_SEC = 9, ENV_FALL_PER_SEC = 2.4
    envBass = slewEnv(envBass, clamp(f.bass, 0, 1.5), d, 9, 2.4);
    envTreble = slewEnv(envTreble, clamp(f.treble, 0, 1.5), d, 9, 2.4);
    beatPulse = clamp(
      Math.max(motionImpulse(f) * clamp(p.beatResponse, 0, 2), beatPulse - d * 3), 0, 1.5,
    );
    // BEAT_THRESHOLD = 0.3
    const reaim = beatImpulseOf(f) * p.beatResponse > 0.3 ? style.reaim : 0;

    const agentU = {
      uAgents: { t: 'tex', v: 0 },
      uTrail: { t: 'tex', v: 1 },
      uTrailRes: { t: '2f', v: [trailW, trailH] },
      uInit: { t: '1f', v: agentsSeeded ? 0 : 1 },
      uSpeciesMix: { t: '1f', v: style.speciesMix },
      uSensorDist: { t: '1f', v: style.sensorDist },
      uSensorAngle: { t: '1f', v: style.sensorAngle },
      uTurnAngle: { t: '1f', v: style.turnAngle },
      uMoveStep: { t: '1f', v: style.moveStep * speed * (1 + 0.5 * envBass * drive) },
      uMatrix: { t: '4f', v: [style.selfA, style.crossAb, style.crossBa, style.selfB] },
      uBreath: { t: '1f', v: beatPulse * drive },
      uJitter: { t: '1f', v: style.jitter + 0.35 * envTreble * drive + clamp(p.turbulence, 0, 1) * 0.5 },
      uSnap: { t: '1f', v: style.snap },
      uReaim: { t: '1f', v: reaim },
      uTime: { t: '1f', v: time },
      uAniso: { t: '1f', v: style.aniso },
    };
    const depositU = {
      uAgents: { t: 'tex', v: 0 },
      uAgentRes: { t: '2f', v: [agentRes, agentRes] },
      uDeposit: { t: '1f', v: style.deposit * (byteTrail ? BYTE_FALLBACK_DEPOSIT : 1) },
    };
    const blurU = {
      uTrail: { t: 'tex', v: 0 },
      uTrailRes: { t: '2f', v: [trailW, trailH] },
      uDecay: { t: '1f', v: Math.pow(style.decay, d * 60) },
    };
    const showU = {
      uTrail: { t: 'tex', v: 0 },
      uRes: { t: '2f', v: [width, height] },
      uTrailRes: { t: '2f', v: [trailW, trailH] },
      uLook: { t: '1i', v: style.look },
      uBaseHue: { t: '1f', v: H.FluidHue.base(p.paletteBase) + style.hueOffset },
      uHueSpan: { t: '1f', v: H.FluidHue.span(p.hueRange, p.paletteRange) * style.hueSpan },
      uExposure: { t: '1f', v: style.exposure * (byteTrail ? 1 / BYTE_FALLBACK_DEPOSIT : 1) },
      uEnergy: { t: '1f', v: clamp(f.rms, 0, 1.5) },
      uBeat: { t: '1f', v: beatPulse },
    };

    agentsSeeded = true;

    return {
      uniforms: null,
      melt: null,
      passes: [
        // 1. agents sense, turn, walk (uInit=1 writes a fresh population).
        {
          program: 'agent', target: 'agents', draw: 'triangle', blend: null, swap: true,
          textures: [
            { unit: 0, target: 'agents', side: 'read' },
            { unit: 1, target: 'trail', side: 'read' },
          ],
          uniforms: agentU,
        },
        // 2. deposit: one additive point per agent, into the trail's READ
        //    side (no swap - the app binds field.read.fbo here).
        {
          program: 'deposit', target: 'trail', side: 'read', swap: false,
          draw: { points: agentRes * agentRes }, blend: 'add',
          textures: [{ unit: 0, target: 'agents', side: 'read' }],
          uniforms: depositU,
        },
        // 3. diffuse + decay, read -> write, swap.
        {
          program: 'blur', target: 'trail', draw: 'triangle', blend: null, swap: true,
          textures: [{ unit: 0, target: 'trail', side: 'read' }],
          uniforms: blurU,
        },
        // 4. present.
        {
          program: 'show', target: 'screen', draw: 'triangle', blend: null,
          textures: [{ unit: 0, target: 'trail', side: 'read' }],
          uniforms: showU,
        },
      ],
      debug: { time: round4(time), reaim: round4(reaim), beatPulse: round4(beatPulse) },
    };
  }

  /** MycoScene mirrors the byte-trail deposit/exposure rescale. */
  function onInit(init) {
    const trail = init && init.fieldSim && init.fieldSim.targets && init.fieldSim.targets.trail;
    byteTrail = !!trail && trail.format === 'rgba8';
  }

  function jumpClock(seconds) {
    time = (time + seconds) % FIELD_TIME_WRAP;
  }

  return {
    id: 'myco', supplies, step, jumpClock, onInit,
    meltConfig: { enabled: false },
    fieldPrograms: {
      agent: ['quad_vert', 'myco_agent_frag'],
      deposit: ['myco_deposit_vert', 'myco_deposit_frag'],
      blur: ['quad_vert', 'myco_blur_frag'],
      show: ['quad_vert', 'myco_show_frag'],
    },
    fieldTargets: {
      // "Positions need real precision": rgba32 ?: rgba, NEAREST.
      agents: { width: agentRes, height: agentRes, format: 'rgba32f', filter: 'nearest' },
      // fmt.rg (RG16F) linear; RGBA8 fallback triggers the deposit rescale.
      trail: { width: trailW, height: trailH, format: 'rg16f', filter: 'linear' },
    },
    standIns: [
      'the PcmPulse runs on the audio model\'s waveform (MycoScene tracks the strike '
      + 'but its draw() reads only envelopes, so nothing visible depends on it)',
    ],
  };
}

function round4(x) {
  return Math.round(x * 10000) / 10000;
}

// ---------------------------------------------------------------------------
// Registry for preview.mjs: one entry per field-sim family.
// ---------------------------------------------------------------------------

export const FIELD_FAMILIES = {
  silk: {
    styles: SILK_STYLES,
    create: createSilkDriver,
    kotlin: 'render/scene/SilkScene.kt',
    blurb: 'SilkScene.kt + silk_step/silk_show',
  },
  life: {
    styles: LIFE_STYLES,
    create: createLifeDriver,
    kotlin: 'render/scene/LifeScene.kt',
    blurb: 'LifeScene.kt + life_step/life_show',
  },
  acid: {
    styles: ACID_STYLES,
    create: createAcidDriver,
    kotlin: 'render/scene/AcidScene.kt',
    blurb: 'AcidScene.kt + acid_step/acid_show',
  },
  myco: {
    styles: MYCO_STYLES,
    create: createMycoDriver,
    kotlin: 'render/scene/MycoScene.kt',
    blurb: 'MycoScene.kt + myco_agent/deposit/blur/show',
  },
};
