#!/usr/bin/env node
// Headless preview for the app's GPU styles. See README.md - in particular
// the section on what this tool CANNOT tell you.
//
//   node preview.mjs --scene hyperspace --frames 8 --audio beat --out out/hs
//   node preview.mjs --scene shader --shader julia_frag --audio tone
//   node preview.mjs --list

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { launch } from './lib/cdp.mjs';
import { parseIncludeRegistry, loadShader, parseUniforms } from './lib/glsl.mjs';
import { extractUploadedUniforms, auditUniforms } from './lib/kotlin.mjs';
import { MODELS } from './lib/audio.mjs';
import { createHyperspaceDriver, createParticleDriver, createShaderSceneDriver } from './lib/scenes.mjs';
import { createCompositeDriver } from './lib/composite.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const APP = path.resolve(HERE, '../../app/src/main');
const RAW = path.join(APP, 'res/raw');
const JAVA = path.join(APP, 'java/dev/musicviz');
const GLUTIL = path.join(JAVA, 'render/scene/GlUtil.kt');

/**
 * The 22 GLSL styles, keyed as `SHADER_SCENES` in VisualizerRenderer.kt.
 * Read out of the Kotlin so a style added there shows up here.
 */
function shaderSceneMap() {
  const src = fs.readFileSync(path.join(JAVA, 'render/VisualizerRenderer.kt'), 'utf8');
  const block = src.match(/SHADER_SCENES[^=]*=\s*(?:linkedMapOf|mapOf|hashMapOf)\(([\s\S]*?)\n\s*\)/);
  if (!block) throw new Error('could not find SHADER_SCENES in VisualizerRenderer.kt');
  const out = new Map();
  const re = /SceneIds\.(\w+)\s+to\s+R\.raw\.(\w+)/g;
  let m;
  while ((m = re.exec(block[1])) !== null) out.set(m[1].toLowerCase(), m[2]);
  return out;
}

function parseArgs(argv) {
  const a = {
    scene: 'hyperspace',
    shader: null,
    frames: 6,
    fps: 60,
    width: 480,
    height: 480,
    audio: 'beat',
    out: null,
    seed: 12345,
    warmup: 0,
    everyFrame: 1,
    clockJump: 0,
    hasMelt: true,
    count: 2048,
    stretchScale: 1,
    stretchMax: 2,
    params: {},
    fieldStats: false,
    composite: false,
    layer: null,
    list: false,
    json: false,
  };
  for (let i = 2; i < argv.length; i++) {
    const k = argv[i];
    const next = () => argv[++i];
    switch (k) {
      case '--scene': a.scene = next(); break;
      case '--shader': a.shader = next(); break;
      case '--frames': a.frames = Number(next()); break;
      case '--fps': a.fps = Number(next()); break;
      case '--width': a.width = Number(next()); break;
      case '--height': a.height = Number(next()); break;
      case '--size': { const s = Number(next()); a.width = s; a.height = s; break; }
      case '--audio': a.audio = next(); break;
      case '--out': a.out = next(); break;
      case '--seed': a.seed = Number(next()); break;
      case '--warmup': a.warmup = Number(next()); break;
      case '--every': a.everyFrame = Number(next()); break;
      case '--clock-jump': a.clockJump = Number(next()); break;
      case '--no-melt': a.hasMelt = false; break;
      case '--count': a.count = Number(next()); break;
      case '--stretch-scale': a.stretchScale = Number(next()); break;
      case '--stretch-max': a.stretchMax = Number(next()); break;
      case '--field-stats': a.fieldStats = true; break;
      case '--composite': a.composite = true; break;
      case '--layer': {
        // "mix,mode" - forces the Layers branch of composite_frag. See
        // createCompositeDriver's `layer` parameter for what this can and
        // cannot tell you.
        const [mix, mode] = next().split(',');
        a.layer = { mix: Number(mix), mode: Number(mode || 0) };
        break;
      }
      case '--list': a.list = true; break;
      case '--json': a.json = true; break;
      case '--param': {
        const [name, value] = next().split('=');
        a.params[name] = value === 'true' ? true : value === 'false' ? false : Number(value);
        break;
      }
      default: throw new Error(`unknown flag ${k}`);
    }
  }
  return a;
}

function makeDriver(args) {
  if (args.scene === 'hyperspace') {
    return {
      driver: createHyperspaceDriver({
        params: args.params, width: args.width, height: args.height,
        seed: args.seed, hasMelt: args.hasMelt,
      }),
      fragResource: 'hyperspace_frag',
      kotlinPath: path.join(JAVA, 'render/scene/HyperspaceScene.kt'),
      // The app binds both samplers unconditionally but only ever names them
      // via loc(); they ARE in the Kotlin, so nothing is ignored here.
      ignoreUploaded: [],
      standIns: [],
      // CompositeGrade.SceneFamily: HyperspaceScene is none of ShaderScene,
      // ParticleSceneBase or ProjectMScene, so it lands in the else branch.
      family: 'FLUID',
    };
  }
  if (args.scene === 'shader') {
    const map = shaderSceneMap();
    if (!args.shader) throw new Error(`--scene shader needs --shader; try --list`);
    const resource = map.get(args.shader) || (args.shader.endsWith('_frag') ? args.shader : null);
    if (!resource) throw new Error(`unknown shader style '${args.shader}'; try --list`);
    return {
      driver: createShaderSceneDriver({
        params: args.params, width: args.width, height: args.height,
      }),
      fragResource: resource,
      kotlinPath: path.join(JAVA, 'render/scene/ShaderScene.kt'),
      ignoreUploaded: [],
      standIns: [
        'uFlow = 1x1 black, uFlowStrength = 0 (the app\'s state for a scene not wired to the FlowField)',
        'uPalLut = 1x1 black, uPalLutMix = 0 (the app\'s state when the cyclic-palette atlas is absent)',
      ],
      family: 'SHADER',
    };
  }
  if (args.scene === 'particles') {
    return {
      driver: createParticleDriver({
        params: args.params, width: args.width, height: args.height,
        count: args.count, seed: args.seed,
        stretchScale: args.stretchScale, stretchMax: args.stretchMax,
      }),
      // The only scene here with a vertex shader of its own: the billboard and
      // the stretch are vertex-stage work, so half the uniform contract lives
      // there and the audit has to read both stages.
      vertResource: 'particle_vert',
      fragResource: 'particle_frag',
      kotlinPath: path.join(JAVA, 'render/scene/ParticleSceneBase.kt'),
      ignoreUploaded: [],
      standIns: [
        'the particle POPULATION is a stand-in, not any style\'s simulate(): a deterministic ' +
        'orbit field spanning the full size/speed/hue range (every 64th particle dead, so the ' +
        'vFade<=0 discard path runs). The nine simulate()s are pure CPU and are covered on the ' +
        'JVM by ParticleStyleTest/NewParticleStylesTest via particleRecords().',
        'no FlowField is bound, so applyFlowField takes its early return - the same state as a ' +
        'style with flowEnabled off',
      ],
      family: 'PARTICLE',
    };
  }
  throw new Error(`unknown --scene '${args.scene}' (hyperspace | shader | particles)`);
}

async function main() {
  const args = parseArgs(process.argv);
  const registry = parseIncludeRegistry(GLUTIL);

  if (args.list) {
    const map = shaderSceneMap();
    console.log(`includes registered in GlUtil: ${[...registry].join(', ')}`);
    console.log('\nscenes:');
    console.log('  hyperspace                 (HyperspaceScene.kt + hyperspace_frag.glsl)');
    console.log('  particles                  (ParticleSceneBase.kt + particle_vert/frag.glsl)');
    console.log('                             the SHARED render path of the nine CPU particle');
    console.log('                             styles, with a stand-in population - see --json');
    console.log('                             standIns. Flags: --count --stretch-scale --stretch-max');
    console.log('  shader --shader <id>       (ShaderScene.kt), one of:');
    for (const [id, res] of map) console.log(`    ${id.padEnd(12)} -> ${res}.glsl`);
    console.log(`\naudio models: ${Object.keys(MODELS).join(', ')}`);
    return;
  }

  const {
    driver, fragResource, kotlinPath, ignoreUploaded, standIns, family,
    vertResource = 'quad_vert',
  } = makeDriver(args);

  const vertSrc = loadShader(RAW, vertResource, registry);
  const fragSrc = loadShader(RAW, fragResource, registry);
  // Uniforms from BOTH stages. quad_vert declares none, so this is a no-op for
  // every fullscreen style; particle_vert declares six of the thirteen, and
  // auditing only the fragment stage would have silently excused all six.
  const declared = (() => {
    const byName = new Map();
    for (const d of [...parseUniforms(vertSrc), ...parseUniforms(fragSrc)]) {
      if (!byName.has(d.name)) byName.set(d.name, d);
    }
    return [...byName.values()];
  })();
  const uploaded = extractUploadedUniforms(kotlinPath);
  const audit = auditUniforms({
    sceneId: args.scene,
    declared,
    uploaded,
    supplied: driver.supplies,
    ignoreUploaded,
  });

  // The composite pass gets the SAME three-way audit as a scene: its shader is
  // composite_frag, its Kotlin is VisualizerRenderer's cLoc() block, and a
  // uniform any of the three is missing would be a silent zero in the pass the
  // user actually looks at.
  let compositeDriver = null;
  let compositeShaders = null;
  let compositeCounts = null;
  if (args.composite) {
    compositeDriver = createCompositeDriver({
      params: args.params, family, width: args.width, height: args.height,
      layer: args.layer,
    });
    compositeShaders = {
      vert: loadShader(RAW, 'fade_vert', registry),
      frag: loadShader(RAW, 'composite_frag', registry),
    };
    const compositeDeclared = parseUniforms(compositeShaders.frag);
    const compositeUploaded = extractUploadedUniforms(path.join(JAVA, 'render/VisualizerRenderer.kt'));
    const compositeAudit = auditUniforms({
      sceneId: 'composite',
      declared: compositeDeclared,
      uploaded: compositeUploaded,
      supplied: compositeDriver.supplies,
      // Uploaded by the renderer, but to ANOTHER program: the crossfade has a
      // shader of its own and is not the composite.
      ignoreUploaded: ['uFadeAlpha'],
    });
    compositeCounts = {
      shaderDeclares: compositeDeclared.length,
      kotlinUploads: compositeUploaded.size - 1,
      harnessSupplies: compositeDriver.supplies.size,
    };
    audit.errors.push(...compositeAudit.errors);
    audit.notes.push(...compositeAudit.notes);
    standIns.push(...compositeDriver.standIns);
  }

  const report = {
    scene: args.scene,
    composite: args.composite ? `composite_frag.glsl, gate ${family}` : null,
    shader: `${fragResource}.glsl`,
    kotlin: path.relative(path.resolve(HERE, '../..'), kotlinPath),
    includesResolved: (fs.readFileSync(path.join(RAW, `${fragResource}.glsl`), 'utf8')
      .match(/^[ \t]*\/\/#include[ \t]+(\w+)[ \t]*$/gm) || []).map((s) => s.trim()),
    uniformAudit: {
      shaderDeclares: declared.length,
      kotlinUploads: uploaded.size,
      harnessSupplies: driver.supplies.size,
      errors: audit.errors,
      notes: audit.notes,
      standIns,
      composite: compositeCounts,
    },
    frames: [],
  };

  if (audit.errors.length) {
    console.error('UNIFORM AUDIT FAILED - the render below would not be trustworthy:\n');
    for (const e of audit.errors) console.error('  ' + e);
    console.error('\nFix lib/scenes.mjs (or the app) before believing any image from this run.');
    process.exitCode = 2;
    return;
  }

  const meltShaders = args.scene === 'hyperspace' && args.hasMelt
    ? {
      vert: loadShader(RAW, 'fluid_base_vert', registry),
      frags: {
        splat: loadShader(RAW, 'fluid_splat_frag', registry),
        advect: loadShader(RAW, 'fluid_advect_frag', registry),
        curl: loadShader(RAW, 'fluid_curl_frag', registry),
        vorticity: loadShader(RAW, 'fluid_vorticity_frag', registry),
        divergence: loadShader(RAW, 'fluid_divergence_frag', registry),
        pressure: loadShader(RAW, 'fluid_pressure_frag', registry),
        gradient: loadShader(RAW, 'fluid_gradient_frag', registry),
        clear: loadShader(RAW, 'fluid_clear_frag', registry),
      },
    }
    : null;

  const model = MODELS[args.audio];
  if (!model) throw new Error(`unknown audio model '${args.audio}' (${Object.keys(MODELS).join(', ')})`);
  const audioAt = model();

  const outDir = args.out ? path.resolve(process.cwd(), args.out) : null;
  if (outDir) fs.mkdirSync(outDir, { recursive: true });

  const browser = await launch();
  try {
    const page = await browser.openPage(pathToFileURL(path.join(HERE, 'page/harness.html')).href);
    const init = await page.call('__init', {
      width: args.width,
      height: args.height,
      vertSrc,
      fragSrc,
      meltConfig: driver.meltConfig,
      meltShaders,
      compositeShaders,
      particles: driver.particleConfig || null,
    });
    if (!init.ok) {
      console.error('harness init failed: ' + init.error);
      for (const l of page.drainConsole()) console.error('  ' + l);
      process.exitCode = 3;
      return;
    }
    report.gl = {
      browser: browser.version,
      renderer: init.unmaskedRenderer,
      glsl: init.glsl,
      colorBufferFloat: init.colorBufferFloat,
      melt: init.melt,
    };
    // A declared uniform the linker dropped is not a harness bug; surface it
    // as a shader observation instead.
    const inactive = declared.map((d) => d.name).filter((n) => !init.activeUniforms.includes(n));
    if (compositeShaders) {
      const kept = init.compositeActiveUniforms || [];
      inactive.push(
        ...parseUniforms(compositeShaders.frag).map((d) => d.name).filter((n) => !kept.includes(n)),
      );
    }
    if (inactive.length) report.uniformAudit.deadStrippedByLinker = inactive;

    const dt = 1 / args.fps;
    let simTime = 0;
    let captureIndex = 0;
    const totalSteps = args.warmup + args.frames * args.everyFrame;

    for (let i = 0; i < totalSteps; i++) {
      const f = audioAt(simTime);
      // Clock jumping. Every step is still a NORMAL 1/fps frame - the app
      // clamps its own dt to 1/15 s, so a 60-second dt is a state the app can
      // never be in and a picture drawn from one would be a fiction. What the
      // jump moves is the free-running clocks only (see driver.jumpClock), so
      // an hour of wallpaper uptime costs one step instead of 216 000 and the
      // thing being tested - float precision in uTime - is the thing that
      // actually got an hour older.
      const plan = driver.step(f, dt);
      // After the scene's step, as in the app: the composite grades the frame
      // the scene has just drawn, at the same simulated instant.
      const compositePlan = compositeDriver ? compositeDriver.step(f, dt, simTime + dt) : null;
      simTime += dt;
      // Warm-up runs at real time so the scene is populated before the clock
      // is thrown forward; jumping from a cold start only ever measures an
      // empty scene at a large t.
      if (args.clockJump > 0 && i >= args.warmup && driver.jumpClock) {
        driver.jumpClock(args.clockJump);
        if (compositeDriver) compositeDriver.jumpClock(args.clockJump);
        simTime += args.clockJump;
      }
      const isWarmup = i < args.warmup;
      const isCapture = !isWarmup && (i - args.warmup) % args.everyFrame === 0;
      const res = await page.call('__frame', {
        uniforms: plan.uniforms,
        composite: compositePlan,
        melt: plan.melt,
        audioTex: plan.audioTex,
        particles: plan.particles || null,
        capture: isCapture && !!outDir,
        wantFieldStats: isCapture && args.fieldStats,
        skipRender: !isCapture,
      });
      if (!res.ok) throw new Error('frame failed: ' + res.error);
      if (!isCapture) continue;

      const entry = {
        index: captureIndex,
        simTime: Math.round(simTime * 1000) / 1000,
        ...res.metrics,
        ...plan.debug,
        ...(compositeDriver ? compositeDriver.debug() : {}),
      };
      if (outDir && res.png) {
        const file = path.join(outDir, `frame_${String(captureIndex).padStart(3, '0')}.png`);
        fs.writeFileSync(file, Buffer.from(res.png.split(',')[1], 'base64'));
        entry.png = file;
      }
      report.frames.push(entry);
      captureIndex++;
    }

    report.console = page.drainConsole();
  } finally {
    await browser.close();
  }

  if (args.json) {
    console.log(JSON.stringify(report, null, 2));
  } else {
    printReport(report);
  }
  if (outDir) {
    fs.writeFileSync(path.join(outDir, 'report.json'), JSON.stringify(report, null, 2));
  }
}

function printReport(r) {
  console.log(`scene      ${r.scene}`);
  console.log(`shader     ${r.shader}${r.includesResolved.length ? `  (${r.includesResolved.join(', ')})` : ''}`);
  if (r.composite) console.log(`composite  ${r.composite}`);
  console.log(`kotlin     ${r.kotlin}`);
  console.log(`gl         ${r.gl.renderer}`);
  console.log(`           ${r.gl.glsl}, EXT_color_buffer_float=${r.gl.colorBufferFloat}`);
  if (r.gl.melt) console.log(`melt       ${JSON.stringify(r.gl.melt)}`);
  const a = r.uniformAudit;
  console.log(`uniforms   shader declares ${a.shaderDeclares}, kotlin uploads ${a.kotlinUploads}, harness supplies ${a.harnessSupplies} - AUDIT OK`);
  if (a.composite) {
    const c = a.composite;
    console.log(`           composite declares ${c.shaderDeclares}, renderer uploads ${c.kotlinUploads}, harness supplies ${c.harnessSupplies} - AUDIT OK`);
  }
  for (const s of a.standIns) console.log(`  stand-in: ${s}`);
  for (const n of a.notes) console.log(`  note: ${n}`);
  if (a.deadStrippedByLinker) console.log(`  dead-stripped by linker: ${a.deadStrippedByLinker.join(', ')}`);
  console.log('');
  const cols = ['t', 'meanLuma', 'maxLuma', 'blown>0.95', 'black<0.02', 'dMeanLuma'];
  console.log(cols.map((c, i) => c.padStart(i === 0 ? 8 : 11)).join(''));
  for (const f of r.frames) {
    console.log(
      String(f.simTime).padStart(8)
      + String(f.meanLuma).padStart(11)
      + String(f.maxLuma).padStart(11)
      + String(f.fracBlownOut).padStart(11)
      + String(f.fracBlack).padStart(11)
      + String(f.deltaMeanLuma === null ? '-' : f.deltaMeanLuma).padStart(11)
      + (f.dye ? `   dye max ${f.dye.max} mean ${f.dye.mean}` : '')
      + (f.actName ? `   ${f.actName} n=${f.bloomCount}` : '')
      + (f.postRotation === undefined ? '' : `   postRot ${f.postRotation}`),
    );
  }
  if (r.frames.some((f) => f.png)) {
    console.log(`\npngs: ${path.dirname(r.frames.find((f) => f.png).png)}`);
  }
  if (r.console && r.console.length) {
    console.log('\npage console:');
    for (const l of r.console) console.log('  ' + l);
  }
}

main().catch((e) => {
  console.error(e.stack || String(e));
  process.exitCode = 1;
});
