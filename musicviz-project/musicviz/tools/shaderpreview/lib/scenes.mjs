// Per-frame uniform plans, mirroring the Kotlin scenes' draw() methods.
//
// Each driver returns, for every frame: the uniform map the shader will be
// given, the set of uniform NAMES it claims to supply (audited against the
// Kotlin source by lib/kotlin.mjs), and any per-frame side data the harness
// page needs (the melt's splat queue).
//
// Uniform values are tagged with their setter so the page can upload them
// without guessing: '1f' '1i' '2f' '3f' '4fv' 'm3fv' 'tex'.

import * as H from './hyperspace-math.mjs';
import { MeltEmitters } from './emitters.mjs';
import { audioTexRows, motionImpulse } from './audio.mjs';

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
    'uResolution', 'uTime', 'uBloomCount', 'uBloomPos', 'uBloomShape', 'uBloomLook', 'uBloomRot',
    'uCamPos', 'uCamBasis', 'uFov', 'uSteps', 'uIters', 'uBulbIters', 'uFar', 'uMaxStep', 'uHitEps',
    'uBoundMargin', 'uField', 'uMirror', 'uMirrorFolds', 'uGlow', 'uNeon', 'uHaze',
    'uTrapColor', 'uHueSpread', 'uBaseHue', 'uHueSpan', 'uHasMelt', 'uMelt', 'uFlowGain',
    'uMeltReach', 'uMeltScale', 'uMeltAspect', 'uMeltRelax', 'uStain', 'uLiquid', 'uRidges',
    'uFlowTex', 'uDyeTex', 'uEnergy', 'uBass', 'uTreble', 'uBeat', 'uExposure',
  ]);

  function step(features, dt) {
    time += dt;
    const f = features;
    const impulseRaw = motionImpulse(f);
    const pace = clamp(p.speed, 0.05, 4);

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
      motion: profile.motion * pace * clamp(p.hyperSpin, 0, 3),
      orbitScale: clamp(p.hyperOrbit, 0, 3),
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
// The 22 GLSL styles - render/scene/ShaderScene.kt
// ---------------------------------------------------------------------------

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
      {
        uResolution: { t: '2f', v: [width, height] },
        uAudioTex: { t: 'tex', v: 0 },
        uFlow: { t: 'tex', v: 1 },
        uPalLut: { t: 'tex', v: 2 },
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
