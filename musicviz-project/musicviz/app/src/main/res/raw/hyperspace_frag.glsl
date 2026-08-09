#version 300 es
precision highp float;
// GLSL ES 3.00 defaults fragment sampler2D to LOWP; the half-float melt
// velocity/dye reads (uFlowTex/uDyeTex) would clamp and quantize on GPUs
// honoring sampler precision (Mali) - same guard as every fluid_* pass.
precision highp sampler2D;

// The HYPERSPACE style: a room full of 3D fractals, each one alive on its own
// clock, raymarched with distance estimation.
//
// Everything the eye reads as "alive" is per-BODY and independent: a body has
// its own rotation (uBloomRot, two composed axis-angle spins), its own place
// (uBloomPos, an ellipse in its own plane), its own species (uBloomShape.x),
// its own colour (uBloomLook.x) and its own life (uBloomShape.w, 0 at birth
// and at death). None of them share a clock, so they turn past each other
// instead of turning together, and the room is always in the middle of
// gaining one and losing another. HyperspaceMath.kt owns all of that; this
// shader only draws what it is handed.
//
// The six distance estimators below are the published constructions - see
// THIRD_PARTY_NOTICES for the technique references - written here from the
// mathematics rather than ported. Four of them share one shape: fold space,
// then invert or scale it, and keep the running scale factor so the estimate
// can be pulled back to world units. The other two (BULB, SEED) are
// escape-time formulas that carry a derivative instead of a scale factor, and
// end on the same division. That is why six different fractals fit in one
// loop.
//
// Cost control, because eight distance-estimated fractals per step would not
// run on a phone: every body carries a bounding sphere, and outside it the ray
// steps by the distance to the sphere and the fractal is never touched. A ray
// therefore iterates the one or two bodies it is actually near.

in vec2 vUv;
out vec4 fragColor;

// Compile-time ceilings. The runtime budget (uSteps/uIters/uBulbIters/
// uSeedIters) is a uniform so "Detail" can move without recompiling; these
// only bound the loop. MarchBudget's companion holds the same four numbers and
// HyperspaceUniformParityTest reads both, so a change here fails there.
#define MAX_BLOOMS 8
#define MAX_STEPS 128
#define MAX_ITERS 14
#define MAX_BULB_ITERS 10
#define MAX_SEED_ITERS 12

const float PI = 3.14159265359;

// The optics of the liquid light, per world unit at one full texel of ink.
//
// The dye field is bounded at 1 by construction (MeltMath.DYE_CEILING), so
// these are in units of "a saturated texel" and can be reasoned about instead
// of tuned. Extinction is set so a saturated field is about ONE optical depth
// thick at the default Liquid light: the field is roughly five world units
// across, 0.35 * 0.55 * 5 = 0.96, so the medium veils the geometry behind it
// and does not hide it. Emission is a fraction of that, so the medium still
// absorbs faster than it emits and a thick region settles at about three
// quarters of the ink's own colour rather than blowing past it.
//
// Both moved when the field was bounded, and in opposite directions. The old
// pair (1.6 and 0.35) was chosen against an ink that ran to 60: it made the
// medium opaque many times over across the room while emitting several units
// of radiance, which is a white fog. Against an ink that stops at 1 the same
// pair is a grey one - still opaque, now emitting 0.22 of a colour that is
// itself at most 1, so the medium could only ever subtract light. The units
// these are measured in changed, so they change with them.
const float LIQUID_EXTINCTION = 0.55;
const float LIQUID_EMISSION = 0.40;

uniform vec2 uResolution;
/**
 * Wrapped at HyperspaceMath.TIME_WRAP_SECONDS = 1000 turns of 2*pi. Every
 * sin/cos multiplier of uTime in this file must therefore be a multiple of
 * 0.001 (three decimals or fewer), so the wrap lands on a whole number of
 * turns; floor()-seeded noise (the dust, the grain) reseeds at the wrap,
 * which it already does several times a second by design.
 * HyperspaceReworkTest enforces the rule.
 */
uniform float uTime;
/** Family substyle: 0 original, 1..10 authored variants. */
uniform int uStyle;

// ---- substyle identity ---------------------------------------------------
/**
 * Worst-case Jacobian norm of styleBody()'s deform for this substyle
 * (HyperspaceStyle.lipschitz, always >= 1). The deform runs BEFORE the
 * estimator, so speciesDE bounds the distance in the DEFORMED frame; a twist
 * or shell modulation can overestimate marched-space distance by up to this
 * factor, and a ray stepping the raw estimate walks through thin geometry.
 * map() divides every estimate by it.
 */
uniform float uLipschitz;
/**
 * Floor on the substyle signature weight in styleSky(): identity survives
 * Filigree at 0 instead of eleven styles blanking into one void.
 */
uniform float uStyleFloor;
/**
 * The substyle's own screen pre-fold count, 0 = off. Already gated on the
 * CPU by the act's styleMirror intent (BREAKTHROUGH releases every fold) and
 * rescaled by the user's Mirror-folds control.
 */
uniform float uStyleKaleido;
/** Accent colour: x hue OFFSET from uBaseHue in turns, y saturation,
 *  z blend amount (0 = palette untouched). */
uniform vec3 uStyleTint;
/**
 * Slew-limited bass/mid envelopes (HyperspaceMath.slewLimit): bounded 0..1
 * with a bounded rate of change, which is what licenses them to steer
 * GEOMETRY (fold rotations, shell swell, bulb power) where the raw uBass -
 * and above all uBeat - must stay out of it; see the fold note in map().
 */
uniform float uSlewBass;
uniform float uSlewMid;
/**
 * CPU-integrated substyle phase, wraps at 1 (HyperspaceScene). Consumers
 * multiply it by WHOLE numbers only, so fract()/sin(2*pi*...) of it land
 * exactly where they started when it wraps.
 */
uniform float uStylePhase;
/** 16-bucket smoothed spectrum summary (SpectralSummary). */
uniform float uBands[16];

// ---- the living bodies -------------------------------------------------
uniform int uBloomCount;
/** xyz world centre, w bounding radius (already scaled by the life envelope). */
uniform vec4 uBloomPos[MAX_BLOOMS];
/** x species ordinal, y world scale, z fold constant, w life envelope 0..1. */
uniform vec4 uBloomShape[MAX_BLOOMS];
/** x hue offset, y glow weight, z breath phase, w spare. */
uniform vec4 uBloomLook[MAX_BLOOMS];
/** World -> local rotation, one per body: this is the "rotates separately". */
uniform mat3 uBloomRot[MAX_BLOOMS];

// ---- camera ------------------------------------------------------------
uniform vec3 uCamPos;
/** Column-major right/up/forward. */
uniform mat3 uCamBasis;
uniform float uFov;

// ---- march budget ------------------------------------------------------
uniform int uSteps;
uniform int uIters;
uniform int uBulbIters;
uniform int uSeedIters;
uniform float uFar;
/**
 * Ceiling on one march step, in world units (HyperspaceLook.maxMarchStep).
 * The geometry never needs it - a distance estimate is a lower bound, so a
 * step that size cannot miss anything - but the three integrals taken along
 * the ray do: they are quadratures, and one sample per room is not a
 * quadrature of anything.
 */
uniform float uMaxStep;
uniform float uHitEps;
uniform float uBoundMargin;

// ---- look --------------------------------------------------------------
/** Weight of the background filigree. */
uniform float uField;
/** Kaleidoscopic mirror, 0 = off. */
uniform float uMirror;
uniform float uMirrorFolds;
uniform float uGlow;
uniform float uNeon;
uniform float uHaze;
/** How hard the orbit trap bands a body's colour into nested shells. */
uniform float uTrapColor;
/** How much of the wheel this act spreads over. */
uniform float uHueSpread;
uniform float uBaseHue;
uniform float uHueSpan;
uniform float uEnergy;
uniform float uBass;
uniform float uTreble;
uniform float uBeat;
uniform float uExposure;

// ---- the melt: the fluid the bodies are suspended in, and made of --------
// A full velocity + dye simulation (MeltField.kt), world-anchored: sim space
// IS the world's xy plane over uMeltScale. The bodies stir it as they drift,
// the music and the finger stir it, and it stirs them back.
/** Velocity field, RG = grid velocity. */
uniform sampler2D uFlowTex;
/** Dye field, RGB. */
uniform sampler2D uDyeTex;
/** 0 when the medium is unavailable on this GPU - everything below no-ops. */
uniform float uHasMelt;
/** 0 disables the warp entirely (and skips its texture reads). */
uniform float uMelt;
/**
 * Raw texel -> world displacement at FULL melt. The velocity field is in the
 * sim's own grid units, which are nothing like world units, so the conversion
 * (grid -> sim units/second -> world units/second -> displacement) is folded
 * into one number on the CPU rather than being three multiplies per sample.
 *
 * Deliberately free of uMelt: the medium runs whenever the GPU can give us
 * the buffers, and the amount of it that bends GEOMETRY is one reader's
 * business, not the field's. Ridges reads the same field to find which way
 * the current is going, and used to get a flow of exactly zero whenever Melt
 * was down - a control that silently did nothing unless another one was up.
 */
uniform float uFlowGain;
/**
 * Hard ceiling on |displacement|, in world units. NOT a taste control: it is
 * the same number the CPU inflated every bounding sphere by
 * (`MeltMath.reach`). A spike in the velocity field that displaced further
 * than this would push geometry outside the sphere the ray used to decide
 * whether to look at it at all, and the body would be cut off along a
 * perfect circle.
 */
uniform float uMeltReach;
/** World units per sim unit. */
uniform float uMeltScale;
uniform float uMeltAspect;
/** How much the dye lights the surfaces it has run over. */
uniform float uStain;
/** How much the dye glows in the space between the bodies. */
uniform float uLiquid;
/** Flow-aligned combing of the surface. */
uniform float uRidges;
/** March-step relaxation for the warped domain (MeltMath.stepRelaxation). */
uniform float uMeltRelax;

// Written by map() for the body that owns the nearest distance, so the shading
// below can colour by WHICH body was hit and by where in its iteration the
// point sits. Globals rather than out-parameters: the march calls map() up to
// uSteps times and threading four outputs through it costs registers the
// fractal loops need.
float gTrap;
float gHue;
float gGlow;
/** Scratch for the estimator currently running; promoted into gTrap if it wins. */
float gT;
/** 1 when the nearest distance came from an estimator rather than from a bound. */
float gSurface;
/** Aura weight and weight-averaged hue at this sample - see map(). */
float gAuraW;
float gAuraH;

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

/**
 * The palette. [t] is in turns of the wheel BEFORE the act's spread is
 * applied, so a threshold act stays nearly monochrome and a breakthrough runs
 * the whole spectrum through the same geometry.
 */
vec3 palette(float t, float sat) {
    return hsv2rgb(vec3(fract(uBaseHue + uHueSpan * uHueSpread * t), sat, 1.0));
}

mat2 rot2(float a) {
    float s = sin(a);
    float c = cos(a);
    return mat2(c, -s, s, c);
}

float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

/**
 * Local, bounded deformations for the family substyles. The body's bounding
 * sphere still clips every result, so a profile cannot invalidate the CPU's
 * culling contract or reach outside the raymarch safety envelope.
 *
 * EVERY branch here has a Jacobian bound recorded in the style catalog
 * (HyperspaceStyle.lipschitz) and uploaded as uLipschitz; map() divides the
 * estimate by it, so a new deform is only correct together with a new bound.
 * Rotations and abs() are isometries (bound 1); an additive sine of
 * amplitude A and frequency f adds A*f; a twist of rate k adds k*R over a
 * body of local radius R; a uniform-scale factor multiplies by itself.
 */
vec3 styleBody(vec3 q, float phase) {
    if (uStyle == 1) {
        // Polytope: a kaleidoscopic-IFS cathedral. Three fold-rotate-scale
        // rounds carve the box species into stacked, terraced cells, and the
        // FOLD ROTATION leans on the slew-limited bass, so the architecture
        // visibly reorganises - breathes - on kicks without a single
        // discontinuous frame (rotation is an isometry; the only Lipschitz
        // cost is the scale, 1.24^3, carried in the catalog).
        float ang = 0.42 + 0.30 * uSlewBass + 0.10 * sin(phase);
        mat2 fold = rot2(ang);
        mat2 tilt = rot2(0.31);
        for (int k = 0; k < 3; k++) {
            q = abs(q);
            q.xy = fold * q.xy;
            q.yz = tilt * q.yz;
            q = q * 1.24 - vec3(0.42, 0.30, 0.36) * 0.24;
        }
    } else if (uStyle == 2) { // Liquid Warp: a thin skin carried by the shared fluid field.
        q.z += 0.13 * sin(q.x * 3.1 + phase) + 0.10 * sin(q.y * 2.7 - phase * 0.7);
        q.z *= 0.62;
    } else if (uStyle == 3) {
        // Caduceus: pure axial torsion - two lobes of the bulb wind around
        // each other like the staff's serpents. No additive sine: the twist
        // alone is the identity, and it keeps the Lipschitz bound at
        // 1 + 0.9 * localRadius(BULB) instead of compounding it.
        q.xy = rot2(q.z * 0.9 + phase * 0.18) * q.xy;
    } else if (uStyle == 4) { // Cortex: folded organic ridges.
        q += 0.075 * sin(q.yzx * 4.3 + vec3(phase, phase * 0.7, phase * 1.3));
    } else if (uStyle == 5) { // Reliquary: cut facets around a protected core.
        q = abs(q) - vec3(0.035, 0.06, 0.025);
        q.xz = rot2(0.785 + 0.08 * sin(phase)) * q.xz;
    } else if (uStyle == 7) {
        // Foam: broad pressure shells over the sphere packing. Fewer, wider
        // shells than the first draft (freq 3, not 10): the radial term of
        // the Jacobian is eps*k*R, so the old frequency cost a factor-2 bound
        // for a modulation nobody could tell from this one. The packing's
        // own fold constant also swells on the bass - see map().
        q *= 1.0 + 0.05 * sin(length(q) * 3.0 - phase * 0.55);
    } else if (uStyle == 9) {
        // Plume: stretched, slowly twisting drift - a rising column rather
        // than Caduceus' tight double serpent (a third of the twist rate,
        // plus a sway the serpents deliberately lack).
        q.z *= 0.78;
        q.xy = rot2(q.z * 0.3 + phase * 0.12) * q.xy;
        q.x += 0.07 * sin(q.z * 2.2 + phase * 0.4);
    }
    // Styles 6 (Moire), 8 (Dustskin) and 10 (Wormhole) leave the body clean:
    // their old deforms were invisible (micro-rotations, a displacement below
    // the hit epsilon, a duplicate of Foam's shells) and their identities now
    // live in styleSky() and the full-frame signatures, where they cost one
    // evaluation per pixel instead of one per march step.
    return q;
}

// ========================================================================
//  The melt
// ========================================================================

/** Sim space (x in [-aspect, aspect], y in [-1,1]) -> texture coordinates. */
vec2 simUv(vec2 s) {
    return vec2(s.x / max(uMeltAspect, 1e-3), s.y) * 0.5 + 0.5;
}

/**
 * The medium's velocity at a world point, as a 3D vector, in the world units
 * a point of it drifts over MeltMath.MELT_SECONDS - which is to say, the
 * displacement a full-strength melt would apply.
 *
 * The simulation is two-dimensional, so the third dimension is borrowed: the
 * field is sampled twice, once on the world's xy plane and once on its zy
 * plane, and the two are woven into one vector. Two fetches rather than the
 * three a full tri-planar sample would take - this runs on every march step,
 * and on every normal and occlusion tap after it, so the third fetch would
 * cost more than the extra coherence is worth at these amplitudes.
 *
 * Returns zero only when there IS no medium, so every reader of the current
 * sees the same field. What each reader does with it is its own control.
 */
vec3 flowAt(vec3 p) {
    if (uHasMelt < 0.5) return vec3(0.0);
    vec3 s = p / max(uMeltScale, 0.05);
    vec2 a = texture(uFlowTex, simUv(s.xy)).xy;
    vec2 b = texture(uFlowTex, simUv(vec2(s.z, s.y))).xy;
    // a pushes x and y, b pushes z and y; y is the axis both saw, so it is
    // averaged rather than counted twice.
    return vec3(a.x, (a.y + b.y) * 0.5, b.x) * uFlowGain;
}

/**
 * How far the medium displaces the geometry at a world point.
 *
 * Returns zero when the warp is off, which is what makes "Melt" at 0 an exact
 * no-op rather than a warp of zero size that still costs the fetches.
 */
vec3 meltAt(vec3 p) {
    if (uMelt <= 0.001) return vec3(0.0);
    vec3 v = flowAt(p) * uMelt;
    // Clamped to the reach the bounding spheres were inflated by. A beat can
    // put a large spike in one corner of the velocity field, and without this
    // that one frame would displace geometry clean out of the sphere the ray
    // culled it with.
    float m = length(v);
    return m > uMeltReach ? v * (uMeltReach / max(m, 1e-6)) : v;
}

/**
 * The dye at a world point, and zero where there is no dye.
 *
 * The field is a FINITE volume of ink - two sim units square - and the room
 * is several times larger than it, so most of what a ray passes through is
 * off the grid entirely. CLAMP_TO_EDGE answers a sample out there with the
 * boundary texel, extended outward forever, which is how a stray colour on
 * one edge of the field became a razor-sharp horizon across a whole empty
 * frame. Unlike the velocity field - whose extrapolation only bends geometry,
 * and only ever by uMeltReach - this one is read as RADIANCE, so an
 * extrapolated value is light the scene invents. Outside the grid there is no
 * ink; the border is a couple of texels wide so the field ends rather than
 * stops.
 */
vec3 dyeAt(vec3 p) {
    if (uHasMelt < 0.5) return vec3(0.0);
    vec2 uv = simUv((p / max(uMeltScale, 0.05)).xy);
    vec2 edge = min(uv, 1.0 - uv);
    float inside = smoothstep(0.0, 0.02, min(edge.x, edge.y));
    if (inside <= 0.0) return vec3(0.0);
    return texture(uDyeTex, uv).rgb * inside;
}

// ========================================================================
//  Distance estimators
//
//  Each returns a lower bound on the distance to its fractal in LOCAL units,
//  and leaves its orbit trap - the closest the iteration came to the origin -
//  in gT. The trap is what colours the nested shells: two points on the same
//  surface that escaped at different depths get different hues, which is
//  exactly the banding these fractals are drawn for.
// ========================================================================

/**
 * GASKET - the sphere packing.
 *
 * The limit set of the Kleinian group generated by (a) translation of the unit
 * cell, applied as a fold, and (b) inversion in the sphere of radius
 * sqrt(fold). Every gap between spheres fills with more spheres, forever; the
 * running product of the inversion factors is the scale the estimate is pulled
 * back through.
 */
float deGasket(vec3 p, float fold) {
    float s = 1.0;
    for (int i = 0; i < MAX_ITERS; i++) {
        if (i >= uIters) break;
        // Fold into the unit cell: the translation generators.
        p = -1.0 + 2.0 * fract(0.5 * p + 0.5);
        float r2 = dot(p, p);
        gT = min(gT, r2);
        // Inversion. The floor on r2 is not cosmetic - the group's fixed point
        // sits at the origin and the raw reciprocal returns inf there.
        float k = fold / max(r2, 1e-4);
        p *= k;
        // Capped: k can reach ~1.3e4 at the r2 floor, and fourteen of those
        // pass float32 max - s went inf, the estimate went 0/inf = 0, and the
        // ray reported a surface that is not there. 1e30 keeps two orders of
        // headroom under FLT_MAX while making the estimate merely tiny (an
        // underestimate is safe; the march creeps and the eps floor catches it).
        s = min(s * k, 1e30);
    }
    // The limit set accumulates on the plane the cell fold leaves invariant.
    return 0.25 * abs(p.y) / s;
}

/**
 * TEMPLE - kaleidoscopic IFS.
 *
 * Absolute value folds the space into one octant; three conditional swaps fold
 * that octant into a sixth of itself by reflecting in the planes x=y, x=z, y=z;
 * then scale by three about an offset. That is the Menger sponge. The rotation
 * at the end of each iteration is the "kaleidoscopic" part, and it is what
 * turns a sponge into terraced architecture - stepped domes stacked into each
 * other, which is what the reference paintings are full of.
 *
 * Every operation is an isometry or a uniform scale, so the box distance at
 * the end divided by the accumulated scale is still a valid estimate. That
 * word "every" is load-bearing, and it is where this used to fail: the z fold
 * was written `if (p.z < -1.0) p.z += 2.0`, a CONDITIONAL TRANSLATION, and it
 * was dead as well as wrong. Dead because `abs()` and the three sorting swaps
 * leave p.x >= p.y >= p.z >= 0 and the offset subtracts nothing from z, so the
 * branch could never fire and nothing carved the middle third of z. Wrong
 * because even where such a branch does fire it is discontinuous: two points a
 * hair apart across the plane it tests come out two units apart, which makes
 * the estimate an overestimate by an unbounded factor there, and a ray that
 * steps an overestimate walks through the surface. With the sponge alone the
 * discontinuity sits inside the hole it carves and nothing is drawn near it;
 * the moment the rotation carries geometry across that plane, every ray
 * crossing it terminates on a fragment of some deeper iteration instead of on
 * a surface, and the body renders as granular dust - which is what this
 * species did at every Fold and every Detail.
 *
 * The published construction folds z by REFLECTION in the plane z = 1. That is
 * an isometry, it is continuous, and it leaves exactly the same sponge (the two
 * forms differ by a sign that the next iteration's abs() removes).
 *
 * At the top of the Fold range the surface stays finely textured, and that is
 * the construction rather than a fault in it: a kaleidoscopic IFS has detail at
 * every scale, and the sponge's own faces are Sierpinski carpets. What matters
 * is that it is a SURFACE - solid, with square recesses and a rectangular
 * silhouette - and at Fold 0 it is a textbook Menger cube.
 */
float deTemple(vec3 p, float twist) {
    float s = 1.0;
    float c = cos(twist);
    float sn = sin(twist);
    mat2 rot = mat2(c, -sn, sn, c);
    for (int i = 0; i < MAX_ITERS; i++) {
        if (i >= uIters) break;
        p = abs(p);
        if (p.x < p.y) p.xy = p.yx;
        if (p.x < p.z) p.xz = p.zx;
        if (p.y < p.z) p.yz = p.zy;
        gT = min(gT, dot(p, p));
        p = p * 3.0 - vec3(2.0, 2.0, 0.0);
        // The axis the sponge treats differently: the middle third of z is
        // what makes it a sponge rather than a solid block, and this is the
        // mirror that carves it.
        p.z = 1.0 - abs(p.z - 1.0);
        s *= 3.0;
        p.xz = rot * p.xz;
    }
    vec3 d = abs(p) - vec3(1.0);
    return (min(max(d.x, max(d.y, d.z)), 0.0) + length(max(d, 0.0))) / s;
}

/**
 * JEWEL - the box.
 *
 * Reflect anything outside the unit box back in (the box fold), magnify
 * anything inside the inner sphere (the sphere fold), scale, and add the seed
 * back. The two folds are conformal on their own domains, so the derivative
 * can be carried alongside as a scalar and the estimate is length/derivative.
 *
 * A negative scale folds the box inside out, which is where the flat faceted
 * panels meeting at hard edges come from - the surfaces that take a neon rim
 * best, and the ones the tiled, outlined plates in the reference art read as.
 */
float deJewel(vec3 p, float scale) {
    const float MIN_R2 = 0.25;
    const float FIX_R2 = 1.0;
    vec3 seed = p;
    float dr = 1.0;
    for (int i = 0; i < MAX_ITERS; i++) {
        if (i >= uIters) break;
        p = clamp(p, -1.0, 1.0) * 2.0 - p;
        float r2 = dot(p, p);
        gT = min(gT, r2);
        if (r2 < MIN_R2) {
            float f = FIX_R2 / MIN_R2;
            p *= f;
            dr *= f;
        } else if (r2 < FIX_R2) {
            float f = FIX_R2 / r2;
            p *= f;
            dr *= f;
        }
        p = scale * p + seed;
        dr = dr * abs(scale) + 1.0;
    }
    return length(p) / abs(dr);
}

/**
 * CORAL - the smooth one.
 *
 * The same two moves as GASKET, but the fold is into a box of half-size [cell]
 * rather than the unit cell, and the inversion is FLOORED at 1: points already
 * outside the unit sphere are left alone. That floor is the whole difference.
 * Without it the group shatters space into a dust of separate spheres; with
 * it, the pieces stay connected and the limit set is a branching system of
 * smooth ridged tubes - the organic growths, not the jewellery.
 *
 * The estimate is the distance to the fundamental domain's boundary, a
 * cylinder capped by a cone, pulled back through the accumulated scale.
 */
float deCoral(vec3 p, float cell) {
    float s = 1.0;
    for (int i = 0; i < MAX_ITERS; i++) {
        if (i >= uIters) break;
        p = 2.0 * clamp(p, -cell, cell) - p;
        float r2 = max(dot(p, p), 1e-4);
        gT = min(gT, r2);
        float k = max(1.0 / r2, 1.0);
        p *= k;
        // Same overflow cap as deGasket's: an inf here turned the terminal
        // expression into inf/inf = NaN, and a NaN distance poisons the
        // whole march (every min() after it is NaN too).
        s = min(s * k, 1e30);
    }
    float rxy = length(p.xy);
    return 0.7 * max(rxy - 0.92, rxy * p.z / max(length(p), 1e-5)) / s;
}

/**
 * BULB - z -> z^n + c, in spherical coordinates.
 *
 * The only estimator here that is an escape-time formula rather than a fold,
 * and the only one that is genuinely round: raising the angles by the power
 * and the radius to the power is a rotation-and-stretch of the sphere, so the
 * result buds and lobes the way a growing thing does. The estimate is the
 * Douady-Hubbard potential, log(r) * r / dr.
 *
 * Transcendentals in the inner loop make it several times the cost of the
 * others, which is why it gets its own smaller iteration budget.
 */
float deBulb(vec3 p, float power) {
    vec3 z = p;
    float dr = 1.0;
    float r = 0.0;
    for (int i = 0; i < MAX_BULB_ITERS; i++) {
        if (i >= uBulbIters) break;
        r = length(z);
        if (r > 2.0) break;
        gT = min(gT, r * r);
        float theta = acos(clamp(z.z / max(r, 1e-5), -1.0, 1.0)) * power;
        // atan(0, 0) is undefined in GLSL (some drivers return NaN, and a
        // NaN here reaches every later iterate). On the polar axis the
        // azimuth is genuinely arbitrary, so 0 is as correct as anything.
        float phi = (abs(z.x) + abs(z.y) > 1e-8) ? atan(z.y, z.x) * power : 0.0;
        dr = pow(r, power - 1.0) * power * dr + 1.0;
        float zr = pow(r, power);
        float st = sin(theta);
        z = zr * vec3(st * cos(phi), st * sin(phi), cos(theta)) + p;
    }
    // Floored: a point that never escaped has log(r) <= 0, and a zero step
    // stalls the march on the spot forever.
    return max(0.5 * log(max(r, 1.0001)) * r / max(dr, 1e-5), 2e-4);
}

/**
 * SEED - the quaternion Julia set, z -> z^2 + c taken in the quaternions.
 *
 * The only estimator here whose iteration lives in FOUR dimensions. The sample
 * point supplies three components of z and [slice] the fourth, so what is
 * drawn is a 3D cross-section of a 4D body. That is where this species' inner
 * life comes from: moving the slice is not a deformation of one surface, it is
 * a different surface cut from the same solid, and it can open a channel or
 * close a neck in a way no scalar fold constant can.
 *
 * Squaring a quaternion costs no more than squaring a complex number. With
 * z = (a, v), a real and v the imaginary vector, z^2 = (a^2 - |v|^2, 2 a v) -
 * four multiplies and a dot product, and not one transcendental in the loop.
 * The derivative is carried as its SQUARE because the recurrence dz -> 2 z dz
 * only ever needs its magnitude, and |dz'|^2 = 4 |z|^2 |dz|^2 is one more
 * multiply rather than a second quaternion product. Together those are what
 * let this run a deeper budget than the bulb at a fraction of its cost.
 *
 * The estimate is the Douady-Hubbard potential written for the square map,
 * 0.5 * |z| * log|z| / |dz|, which is the standard conservative form: it never
 * exceeds the true distance, so the ray cannot step through the surface. Both
 * floors are load-bearing. The one under |z|^2 in the derivative keeps the
 * division finite at the preimages of the origin, where |dz| is genuinely
 * zero. The one inside the log is what makes a point that has not escaped
 * return almost zero instead of a NEGATIVE distance - log of a radius below 1
 * is negative, and a negative estimate marches the ray backwards.
 *
 * Nothing overflows, which is worth stating because the preview harness runs
 * everything at float32 and cannot see a precision limit: the loop leaves the
 * moment |z|^2 passes 4, so every factor in the derivative product is at most
 * 16 and |dz|^2 is at most 16^MAX_SEED_ITERS = 2^48. GLSL ES guarantees highp
 * out to 2^62, which this file declares for every float; it guarantees mediump
 * only to 2^14, so this loop would NOT survive being demoted.
 *
 * c is a CONSTANT and the slice is what moves. That is a safety decision, not
 * a simplification. The set is connected exactly when the orbit of the origin
 * stays bounded, and that orbit lives in the plane spanned by 1 and Im c, so
 * connectedness is the ordinary Mandelbrot test at (Re c, |Im c|) - here
 * (-0.12, 0.711), which is inside the locus with about 0.06 to spare. Walk c
 * across that boundary and the body does not deform, it SHATTERS into a dust
 * in a single frame, which is a large change of projected area in 16 ms - the
 * one thing this family can do that VisualSafety cannot clamp. The slice has
 * no such cliff: it slides the cut through a solid that stays connected.
 *
 * All three imaginary parts are non-zero on purpose. The set is invariant
 * under rotations of the imaginary 3-space that fix Im c, so if Im c lay in
 * the plane this slice keeps, every cross-section would be a solid of
 * revolution - a lathe-turned vase, and the standard disappointment of
 * quaternion Julia sets. Cutting across the symmetry axis is what makes these
 * sections genuinely three-dimensional.
 */
float deSeed(vec3 p, float slice) {
    // |c| <= 0.75 is not a taste: the filled Julia set is contained in the
    // sphere of radius (1 + sqrt(1 + 4|c|)) / 2, which at this |c| is 1.486,
    // and that has to fit inside HyperspaceMath.localRadius(SEED) = 1.5. A
    // bound the camera trusts has to be proved, not measured.
    const vec4 c = vec4(-0.12, 0.44, 0.39, 0.40);
    vec4 z = vec4(p, slice);
    float md2 = 1.0;
    float mz2 = dot(z, z);
    for (int i = 0; i < MAX_SEED_ITERS; i++) {
        if (i >= uSeedIters) break;
        md2 *= 4.0 * max(mz2, 1e-8);
        z = vec4(z.x * z.x - dot(z.yzw, z.yzw), 2.0 * z.x * z.yzw) + c;
        mz2 = dot(z, z);
        gT = min(gT, mz2);
        if (mz2 > 4.0) break;
    }
    return 0.25 * sqrt(mz2 / md2) * log(max(mz2, 1.0001));
}

/**
 * Dispatch on the body's species ordinal (see HyperspaceMath.Species).
 *
 * An if-chain, and every branch is inlined into this one function, so what the
 * register allocator has to accommodate is the WORST estimator rather than the
 * sum of them - adding a species costs one comparison for the bodies that are
 * not it. That is the only reason a sixth fits: deSeed's live set is a vec4, a
 * derivative and a radius, comfortably under deJewel's and deTemple's.
 */
float speciesDE(vec3 p, int species, float fold) {
    if (species == 0) return deGasket(p, fold);
    if (species == 1) return deTemple(p, fold);
    if (species == 2) return deJewel(p, fold);
    if (species == 3) return deCoral(p, fold);
    if (species == 4) return deBulb(p, fold);
    return deSeed(p, fold);
}

/**
 * The scene: the union of every living body, each evaluated in ITS OWN frame.
 *
 * The bounding-sphere gate is what makes this affordable. Outside a body's
 * sphere the distance to the sphere is already a valid lower bound on the
 * distance to the fractal inside it, so the ray can step by that and skip the
 * estimator entirely. The margin exists because a gate at exactly zero makes
 * the march creep: the step lands on the sphere, the bound is zero, and the
 * ray never gets inside.
 */
float map(vec3 p) {
    // The far plane, not a sentinel. With no body in range - which is every
    // sample in an empty room, and every sample on the first frame of a scene,
    // a style switch or a context loss - this is what the march is handed, and
    // it is a DISTANCE the caller steps by. A 1e9 here made that step 4e8
    // world units, which sampled the medium a hundred million units off its
    // own grid and integrated it over the same length; the frame went white.
    // Nothing beyond uFar is ever drawn, so "no surface within the far plane"
    // is both true and the largest useful bound there is.
    float d = uFar;
    gAuraW = 0.0;
    gAuraH = 0.0;
    gSurface = 0.0;
    // THE MELT. One displacement for the whole scene, sampled once per march
    // step: every body is then evaluated at the moved point, so they are all
    // stirred by the same medium and stretch INTO each other instead of each
    // wobbling on its own. This is what turns eight rigid fractals into one
    // moldable substance.
    //
    // The bound below is deliberately measured on the UNMOVED point. The
    // bounding spheres are what let a ray skip seven of the eight bodies, and
    // they only stay valid if they are tested in the frame they were built in
    // - the CPU inflates each radius by the melt's reach (MeltMath.reach) so
    // the sphere still contains the body after the medium has pulled it out
    // of shape.
    vec3 pw = p + meltAt(p);
    for (int i = 0; i < MAX_BLOOMS; i++) {
        if (i >= uBloomCount) break;
        vec4 P = uBloomPos[i];
        vec4 S = uBloomShape[i];
        vec4 L = uBloomLook[i];
        vec3 rel = p - P.xyz;
        float bound = length(rel) - P.w;

        // The aura: every body's own soft light, as a function of its BOUNDING
        // SPHERE rather than of the fractal inside it. That distinction is the
        // whole point - the estimator jumps discontinuously at the sphere (it
        // is not evaluated outside), so lighting the haze with it drew a hard
        // ellipse around every body. The sphere's own distance is smooth
        // everywhere, so this is a halo. Weight and weighted hue, not a colour:
        // one hsv2rgb per body per march step would not be affordable.
        float aw = L.y * (1.0 + 1.3 * (1.0 - S.w)) * exp(-max(bound, 0.0) * 3.2);
        gAuraW += aw;
        gAuraH += aw * L.x;

        if (bound > uBoundMargin) {
            d = min(d, bound);
            continue;
        }
        // Into the body's frame: rotate by ITS rotation, scale by ITS scale -
        // from the MOVED point, so the medium reaches inside the fractal.
        vec3 q = (uBloomRot[i] * (pw - P.xyz)) / max(S.y, 1e-4);
        q = styleBody(q, L.z);
        // The body breathes: a slow wobble of its own fold constant, on its
        // own phase, so a body is never quite the same shape twice - and the
        // bass leans on it, gently and equally for every body.
        //
        // uBeat is deliberately NOT among the structural modulators, here or
        // anywhere. VisualSafety clamps parameters and LFO rates; it cannot
        // clamp geometry, and the hazard in this family is not colour but
        // area - a fold constant that jumped on a transient would change how
        // much of the screen a body covers between one frame and the next.
        // What IS allowed in, beyond the body's own slow phase, is uSlewBass:
        // bounded 0..1 with a bounded rate of change (HyperspaceMath
        // .slewLimit), so every coupling through it is continuous by
        // construction. Foam's packing swells a few per cent on it, and the
        // bulb's power rides it (the mandelbulb breathing), clamped inside
        // the band foldFor(BULB) already draws from.
        int spec = int(S.x + 0.5);
        float fold = S.z * (1.0 + 0.04 * sin(L.z) + 0.02 * uBass
            + ((uStyle == 7) ? 0.035 * uSlewBass : 0.0));
        if (spec == 4) fold = clamp(fold + 1.6 * uSlewBass, 5.0, 11.0);
        gT = 1e9;
        // Divided by uLipschitz: styleBody() deforms the domain BEFORE the
        // estimator, so the estimate bounds distance in the deformed frame
        // and can overestimate marched space by the deform's Jacobian norm.
        // The catalog carries the per-style bound; 1 when nothing deforms.
        float df = speciesDE(q, spec, fold) * max(S.y, 1e-4) / max(uLipschitz, 1.0);
        // Clip the body to its own bounding sphere. Three of the estimators
        // describe UNBOUNDED sets - the gasket's plane, the coral's cylinder,
        // the temple's tiling - so without the intersection a body streaks off
        // across the whole scene as a stripe that ignores where it is supposed
        // to be. It also makes the bound honest: outside the sphere the ray
        // skipped this body, so anything the estimator claimed out there was
        // never going to be shaded consistently anyway.
        df = max(df, bound);
        if (df < d) {
            d = df;
            gTrap = gT;
            gHue = L.x;
            gSurface = 1.0;
            // Brightest while being born and while dissolving: a body arrives
            // as light condensing and leaves as light coming apart, which is
            // what makes a spawn and a despawn read as events rather than as
            // an object popping in and out.
            gGlow = L.y * (1.0 + 1.3 * (1.0 - S.w));
        }
    }
    return d;
}

/** Tetrahedral normal: four taps instead of six, same accuracy. */
vec3 calcNormal(vec3 p, float e) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(
        k.xyy * map(p + k.xyy * e) +
            k.yyx * map(p + k.yyx * e) +
            k.yxy * map(p + k.yxy * e) +
            k.xxx * map(p + k.xxx * e)
    );
}

/** Three-tap ambient occlusion along the normal - enough to seat the folds. */
float calcAO(vec3 p, vec3 n, float scale) {
    float occ = 0.0;
    float w = 1.0;
    for (int i = 1; i <= 3; i++) {
        float h = scale * float(i) * 0.5;
        occ += (h - map(p + n * h)) * w;
        w *= 0.55;
    }
    return clamp(1.0 - 1.6 * occ, 0.0, 1.0);
}

/**
 * The chrysanthemum: the filigree the whole scene sits inside.
 *
 * Evaluated on the RAY DIRECTION, so it is infinitely far away, has no edges
 * and turns with the view - the fabric behind everything rather than an object
 * in front of it. The iteration folds into the positive octant, inverts in the
 * unit sphere and translates; its orbit is a dense symmetric filigree, which is
 * what the opening seconds of the experience this style is named for are
 * always described as.
 *
 * The view turns through it, slowly and on two unrelated rates, so the fabric
 * keeps reorganising instead of standing still.
 */
vec3 chrysanthemum(vec3 rd) {
    if (uField <= 0.002) return vec3(0.0);
    // The VIEW turns; the fractal stands still.
    //
    // The translation constant used to drift instead, on three slow rates, for
    // the same reason - to keep the fabric reorganising. It is not the same
    // thing. This iteration is chaotic in that constant: a drift of 0.07 walks
    // it through parameter values where the whole orbit collapses toward the
    // origin, and then every direction on the screen reads as "on the set" at
    // once. Measured over a simulated hour, the fraction of the sky the traps
    // below light ranged from under a thousandth to nine tenths, on a
    // two-minute cycle. Half of the "washes out" this style was reported for
    // was that cycle; a fixed constant with the view moving through it holds
    // every one of those statistics inside a factor of two, which is what lets
    // the thresholds below be numbers rather than guesses.
    float turn = uTime * 0.037;
    float tilt = uTime * 0.021;
    rd.xz = mat2(cos(turn), -sin(turn), sin(turn), cos(turn)) * rd.xz;
    rd.yz = mat2(cos(tilt), -sin(tilt), sin(tilt), cos(tilt)) * rd.yz;
    // Where the orbit starts. The iteration's own structure is at unit scale,
    // so this is what decides how much of the fabric one screen holds; it
    // breathes, which reads as the fabric drawing itself closer and letting go.
    //
    // Not compensated for the mirror any more. kaleido() maps (r, angle) to
    // (r, fold(angle)) and |d fold / d angle| is 1, so it is a piecewise
    // ISOMETRY of the screen: it repeats a wedge of the field, it does not
    // magnify it. The scale factor that used to be applied here was correcting
    // for a squeeze that does not happen.
    float reach = 4.6 + 0.7 * sin(uTime * 0.05);
    vec3 p = rd * reach;
    const vec3 c = vec3(0.84, 0.91, 0.72);
    // The CLOSEST approach of the orbit, not a sum over it. A sum washes the
    // whole sky to a pastel haze; the closest approach is an orbit trap, and
    // its narrow response is what leaves thin bright strands on black - the
    // difference between a filigree and a fog.
    //
    // Two traps, because a fabric is threads and the knots where they cross.
    // The orbit lies along the coordinate PLANES, so the smallest component is
    // the distance to the nearest thread, and the two smallest together are
    // the distance to the nearest coordinate axis - which is where two threads
    // meet. Neither is the distance to the ORIGIN, which is what the knot trap
    // used to be and which does not vary: over a screenful of directions its
    // closest approach spans four per cent, so through any response at all it
    // is a constant, and a constant added to every pixel is the definition of
    // a wash. The thread trap was the other half: it is the smallest of
    // thirty-six numbers whose median is 0.02, and exp(-x*x*420) of that is
    // 0.45, so nine tenths of the sky came out at four tenths of full
    // brightness before anything was drawn on it.
    float thread = 1e9;
    float knot = 1e9;
    float hue = 0.0;
    for (int i = 0; i < 12; i++) {
        p = abs(p) / max(dot(p, p), 1e-4) - c;
        vec3 a = abs(p);
        float lo = min(min(a.x, a.y), a.z);
        float hi = max(max(a.x, a.y), a.z);
        float mid = a.x + a.y + a.z - lo - hi;
        if (lo < thread) {
            thread = lo;
            hue = float(i) * 0.11 + length(p) * 0.6;
        }
        knot = min(knot, length(vec2(lo, mid)));
    }
    // Each gain is one over the square of its own trap's value at about the
    // fifth percentile of the distribution that trap actually has, so the
    // response is down to 1/e there: between a sixteenth and an eighth of the
    // sky is on a thread or at a knot and the rest is black. With the constant
    // fixed above those percentiles hold across the whole run, which is what
    // makes these calibrated numbers rather than a taste that worked once.
    float strands = exp(-knot * knot * 6.0e3) + 0.55 * exp(-thread * thread * 1.0e6);
    // A thin thing has to be bright to be seen. The old wash carried the sky's
    // light over the whole sky; this carries the same light over a tenth of
    // it, so the per-strand radiance goes up by about that factor and the
    // FULL-SCREEN mean comes out at or below where it was - which is the term
    // VisualSafety cares about, and it is also far steadier over time now that
    // it no longer swings with the constant.
    const float FILIGREE = 5.0;
    // Treble picks out the fine strands. Every factor here is well under a
    // brightness that could read as a flash, at any level (see VisualSafety).
    float sharpen = 1.0 + 0.35 * clamp(uTreble, 0.0, 1.2);
    return palette(hue, 0.82) * strands * FILIGREE * uField
        * (0.20 + 0.13 * clamp(uEnergy, 0.0, 1.2)) * sharpen;
}

/** Angle wrapped to [-PI, PI). */
float wrapAngle(float x) {
    return x - 2.0 * PI * floor(x / (2.0 * PI) + 0.5);
}

/**
 * Hex lattice: offset from the nearest cell centre; the centre itself lands
 * in [id] so a cell can keep one identity (one spectrum bucket) for life.
 */
vec2 hexCell(vec2 p, out vec2 id) {
    const vec2 s = vec2(1.0, 1.7320508);
    vec2 a2 = mod(p, s) - 0.5 * s;
    vec2 b2 = mod(p - 0.5 * s, s) - 0.5 * s;
    if (dot(a2, a2) < dot(b2, b2)) {
        id = p - a2;
        return a2;
    }
    id = p - b2;
    return b2;
}

/** Smoothed spectrum bucket at x in 0..1 (uBands, SpectralSummary). */
float bandAt(float x) {
    return uBands[int(clamp(x, 0.0, 0.999) * 16.0)];
}

/**
 * Additional family atmosphere laid over the shared chrysanthemum.
 *
 * Weighted by max(uField, uStyleFloor), NOT uField alone: the filigree
 * slider owns the shared fabric, but a substyle's signature is its identity
 * and used to blank with it - Filigree at 0 turned eleven styles into one.
 *
 * This is also where the family reads the SPECTRUM (uBands): the hex tunnel
 * lights one bucket per cell, the phyllotaxis one per seed ring, the
 * wormhole one per ring of the descent, the cortex one per angular sector.
 * All 2D, once per pixel - never inside the march loop.
 */
vec3 styleSky(vec3 rd, vec3 base) {
    float a = atan(rd.y, rd.x);
    float r = length(rd.xy) / max(abs(rd.z), 0.22);
    vec3 extra = vec3(0.0);
    if (uStyle == 1) { // Polytope wire cells.
        vec3 d = abs(rd);
        float edge = exp(-abs(max(d.x, max(d.y, d.z)) - 0.72) * 48.0);
        extra = palette(a / (2.0 * PI) + 0.16, 0.62) * edge * 0.24;
    } else if (uStyle == 2) { // Liquid Warp interference carried through the tunnel.
        float wave = exp(-abs(sin(rd.x * 19.0 + uTime * 0.23) + sin(rd.y * 17.0 - uTime * 0.19)) * 3.0);
        extra = palette(r * 0.12, 0.72) * wave * 0.16;
    } else if (uStyle == 3) {
        // Caduceus: TWO helical rails winding in opposite senses - the
        // serpents - with rungs where they cross, like the staff drawn on
        // the sky. The old single trace was one anonymous stripe.
        float s1 = sin(a * 2.0 + rd.z * 9.0 - uTime * 0.35);
        float s2 = sin(a * 2.0 - rd.z * 9.0 + uTime * 0.35);
        float rails = exp(-abs(s1) * 9.0) + exp(-abs(s2) * 9.0);
        float rungs = exp(-abs(sin(rd.z * 22.0 + uTime * 0.14)) * 24.0) * exp(-abs(s1 * s2) * 5.0);
        extra = palette(a / (2.0 * PI) + rd.z * 0.12, 0.78) * rails * 0.16
            + palette(0.5, 0.55) * rungs * 0.30;
    } else if (uStyle == 4) {
        // Cortex: the branching mesh, plus synapses - the mesh's knots fire
        // with their own spectrum bucket, one per angular sector.
        float folds = abs(sin(rd.x * 24.0 + sin(rd.y * 9.0)) * sin(rd.y * 21.0 + sin(rd.z * 11.0)));
        float knots = pow(1.0 - folds, 14.0);
        float lvl = bandAt(fract(a / (2.0 * PI) + 0.5));
        extra = palette(folds * 0.18, 0.84) * pow(1.0 - folds, 8.0) * 0.18
            + palette(0.55 + 0.1 * lvl, 0.7) * knots * lvl * 0.3;
    } else if (uStyle == 5) { // Reliquary facets, and a faint devotional halo.
        vec3 f = abs(rd);
        float facet = pow(max(f.x, max(f.y, f.z)), 18.0);
        float halo = exp(-abs(r - 1.35) * 9.0);
        extra = palette(0.12 + f.y * 0.18, 0.55) * facet * 0.2
            + palette(0.06, 0.35) * halo * 0.13;
    } else if (uStyle == 6) {
        // Moire: the hex-grid tunnel. Tunnel coordinates (1/r flies inward,
        // the angle wraps) tiled with hexagonal cells; each cell keeps one
        // spectrum bucket for its whole flight, the fly speed rides the
        // slewed bass through uStylePhase (integrated on the CPU), and a
        // beat sends a light pulse down the near end of the tunnel. The
        // angular span is 6 lattice periods and the phase advances 8 cells
        // per wrap - whole numbers, so neither seam is visible IN THE
        // LATTICE. The cell IDENTITY has to be wrapped to the same two
        // periods by hand: hash31 is pure fract() arithmetic and is periodic
        // in nothing, so hashing the raw id repainted every cell in the
        // tunnel - new hue, new spectrum bucket - in the single frame
        // uStylePhase wrapped at (every ~4 s at Speed 1, three times a second
        // on a bassy track at Speed 4), and put a permanent seam down the
        // atan branch cut at a = +-PI where the two sides of one cell hashed
        // as different cells.
        float depth = 1.0 / max(r, 0.10);
        vec2 st = vec2(depth * 1.8 + uStylePhase * 8.0, (a / (2.0 * PI)) * 10.392305);
        vec2 id;
        vec2 gv = hexCell(st, id);
        float wall = smoothstep(0.48, 0.30, length(gv));
        float pick = hash31(vec3(mod(id.x, 8.0), mod(id.y, 10.392305), 3.7));
        float lvl = bandAt(pick);
        float fade = exp(-depth * 0.5) * smoothstep(0.06, 0.35, r);
        float pulse = 1.0 + 0.8 * clamp(uBeat, 0.0, 1.0) * exp(-depth * 1.4);
        extra = palette(pick * 0.25 + depth * 0.05, 0.75)
            * wall * (0.12 + 0.85 * lvl) * fade * pulse;
    } else if (uStyle == 7) { // Foam cells.
        vec2 cell = fract((rd.xy / max(abs(rd.z), 0.3)) * 4.0) - 0.5;
        float bubble = exp(-abs(length(cell) - 0.31) * 35.0);
        extra = palette(r * 0.09, 0.52) * bubble * 0.18;
    } else if (uStyle == 8) {
        // Dustskin: the kaliset star nest. abs/invert/subtract, accumulating
        // the orbit's travel - the classic volumetric nebula - with the
        // constant steered by the slewed bass and mid, so the nebula's whole
        // architecture leans with the low end of the track. Stars on top,
        // lit by the top of the spectrum.
        vec3 p3 = rd * (1.9 + 0.5 * sin(uTime * 0.021));
        float cx = 0.83 + 0.12 * uSlewBass;
        float cy = 0.58 + 0.14 * uSlewMid;
        float acc = 0.0;
        float pl = length(p3);
        for (int i = 0; i < 8; i++) {
            p3 = abs(p3) / max(dot(p3, p3), 1e-4) - vec3(cx, cy, cx * 0.62);
            float l = length(p3);
            acc += abs(l - pl);
            pl = l;
        }
        float neb = clamp(acc * 0.05, 0.0, 1.4);
        float star = smoothstep(0.965, 0.995, hash31(floor(rd * 180.0) + floor(uTime * 0.7)));
        extra = palette(0.05 + neb * 0.22, 0.85) * neb * neb * 0.16
            + palette(hash31(rd * 31.0), 0.35) * star * (0.25 + 0.5 * bandAt(0.9));
    } else if (uStyle == 9) {
        // Plume: the phyllotaxis chrysanthemum. Seeds on the golden-angle
        // spiral (r = c*sqrt(n), theta = n*gamma), each lit by the spectrum
        // bucket of its own radius, the whole head turning slowly on
        // uStylePhase. The candidate search walks Fibonacci offsets of the
        // ring index - the spiral's own nearest-neighbour structure - in
        // lattice coordinates, so there is no trig inside the loop. The
        // golden angle is detuned a fraction of a degree by the slewed mids,
        // which sends moire waves rippling across the whole head.
        const float SEED_C = 0.32;
        float rr = r / SEED_C;
        float n0 = rr * rr;
        float ga = 2.3999632 + 0.006 * uSlewMid;
        float spin = uStylePhase * 2.0 * PI;
        float kc = floor(n0 + 0.5);
        float best = 1e9;
        float bestK = 0.0;
        const float FIB[11] = float[11](-21.0, -13.0, -8.0, -5.0, -2.0, 0.0, 2.0, 5.0, 8.0, 13.0, 21.0);
        for (int j = 0; j < 11; j++) {
            float k = kc + FIB[j];
            if (k < 0.0) continue;
            float dth = wrapAngle(k * ga + spin - a);
            float dr = 0.5 * SEED_C * (k - n0) / max(sqrt(k + 1.0), 1.0);
            float ds = min(r, 2.0) * dth;
            float d2 = dr * dr + ds * ds;
            if (d2 < best) {
                best = d2;
                bestK = k;
            }
        }
        float sr = sqrt(bestK) * SEED_C;
        float lvl = bandAt(clamp(sr * 0.24, 0.0, 1.0));
        float seed = exp(-best * 260.0);
        float bloom = exp(-best * 40.0);
        extra = palette(sr * 0.06 + bestK * 0.002, 0.7)
            * (seed * (0.28 + 1.1 * lvl) + bloom * 0.05);
    } else if (uStyle == 10) {
        // Resonant Wormhole: log-polar Droste descent. Tiling log(r) and
        // adding the CPU-integrated phase to that axis is an endless
        // approach - the throat never arrives - whose zoom rate lurches on
        // the bass (phaseBassRate) and whose spokes shear by the slewed
        // mids. Each ring of the descent is an equaliser band, and beats
        // light the near rings. The phase advances 2 whole tiles per wrap.
        float lr = log(max(r, 1e-3));
        float tile = fract(lr * 0.72 - uStylePhase * 2.0);
        float spokes = pow(0.5 + 0.5 * sin((a + lr * 0.9 * uSlewMid) * 7.0), 3.0);
        float lvl = bandAt(tile);
        float ring = exp(-abs(tile - 0.5) * 7.0);
        float throat = exp(-r * 1.4);
        float pulse = 1.0 + 0.9 * clamp(uBeat, 0.0, 1.0) * exp(-abs(r - 0.6) * 3.0);
        extra = (palette(tile * 0.3 + lr * 0.05, 0.8) * ring * (0.10 + 0.7 * lvl) * (0.4 + 0.6 * spokes)
            + palette(0.02, 0.9) * throat * 0.35) * pulse;
    }
    return base + extra * max(uField, uStyleFloor);
}

/**
 * N-fold mirror about the centre of the screen: the chrysanthemum's symmetry.
 *
 * It SNAPS rather than fading. A kaleidoscope is discontinuous by nature -
 * there is no halfway between a mirrored plane and an unmirrored one - and
 * interpolating the two POSITIONS folds the plane over itself, which smears
 * the whole frame into streaks (it looked like motion blur, and it was the
 * first thing wrong with this shader). The snap happens at most once per act
 * change, and a hard change of symmetry is exactly what the transitions in
 * the phenomenology this style follows are described as.
 */
vec2 kaleido(vec2 uv, float folds, float amount) {
    if (amount < 0.5 || folds < 2.0) return uv;
    float r = length(uv);
    float a = atan(uv.y, uv.x);
    float seg = 2.0 * PI / folds;
    float f = abs(mod(a, seg) - seg * 0.5);
    return r * vec2(cos(f), sin(f));
}

void main() {
    // Square pixels, origin at the centre, height-normalized: the composition
    // holds on any aspect instead of stretching on a phone in landscape.
    vec2 uv = (vUv - 0.5) * vec2(uResolution.x / max(uResolution.y, 1.0), 1.0) * 2.0;
    uv = kaleido(uv, uMirrorFolds, uMirror);
    // The substyle pre-fold. No hardcoded folds and no unconditional apply:
    // uStyleKaleido arrives from the catalog ALREADY gated by the act's
    // styleMirror intent (BREAKTHROUGH releases every fold - the act that
    // opens must open for the substyles too) and rescaled by the user's
    // Mirror-folds control. 0 = off, and kaleido() ignores folds < 2.
    uv = kaleido(uv, uStyleKaleido, 1.0);
    if (uStyle == 3) uv = rot2(0.22 * length(uv) + 0.08 * sin(uTime * 0.17)) * uv;

    vec3 ro = uCamPos;
    vec3 rd = normalize(uCamBasis * vec3(uv * uFov, 1.0));

    // Per-pixel start offset. A volumetric integral taken at the same depths
    // on every pixel quantizes into visible concentric shells - the medium
    // looked like contour lines on a map. Jittering the whole march by a
    // fraction of a step turns that banding into fine noise, which the eye
    // reads as grain rather than as structure. The surface epsilon is wider
    // than the jitter, so hits are unaffected.
    float jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    float t = 0.35 + jitter * 0.02;
    float trans = 1.0;
    float hitT = -1.0;
    float hitHue = 0.0;
    float hitTrap = 1e9;
    float hitGlow = 1.0;
    float travelled = 0.0;
    vec3 glow = vec3(0.0);

    for (int i = 0; i < MAX_STEPS; i++) {
        if (i >= uSteps) break;
        vec3 p = ro + rd * t;
        gTrap = 1e9;
        gHue = 0.0;
        gGlow = 1.0;
        float d = map(p);
        // Epsilon grows with distance: a constant one shimmers far away and
        // wastes steps up close.
        float eps = uHitEps * t + 4e-4;
        if (d < eps) {
            hitT = t;
            hitHue = gHue;
            hitTrap = gTrap;
            hitGlow = gGlow;
            travelled = float(i) / float(uSteps);
            break;
        }
        // Emissive haze, integrated along the ray: everything near a surface
        // leaks light, which is what gives these bodies their translucency -
        // they glow from inside rather than being lit from outside.
        //
        // Two terms with two jobs. The tight one hugs the fractal surface and
        // only counts where an estimator actually ran (gSurface), so a ray
        // merely grazing a bounding sphere cannot paint a bright edge in a
        // colour nothing there has. The broad one is the aura, smooth
        // everywhere, and it is what the bodies float in.
        // Relaxed by the melt: warping the domain breaks the estimate's
        // Lipschitz bound, and a ray still stepping the full estimate walks
        // straight through thin geometry (holes and shimmer, not anything
        // that reads as an overshoot). MeltMath.stepRelaxation owns the
        // number; it arrives folded into uMeltRelax.
        //
        // Then bounded three ways, because the estimate alone is a bound on
        // what the ray can HIT and says nothing about what it integrates on
        // the way: by uMaxStep, the scale the medium is defined on, so a
        // single step cannot straddle the whole dye field; by what is left of
        // the ray, so nothing past the far plane is integrated at all; and
        // from below by the hit epsilon, since a step finer than the surface
        // threshold cannot resolve anything and a zero step would spend the
        // rest of the budget standing still.
        float step = max(min(d * 0.82 * uMeltRelax, min(uMaxStep, uFar - t)), eps);
        glow +=
            palette(gHue + log(max(gTrap, 1e-6)) * 0.14 * uTrapColor, 0.78) *
                gGlow * gSurface * exp(-d * 12.0) * step;
        if (gAuraW > 1e-4) {
            glow += palette(gAuraH / gAuraW, 0.80) * gAuraW * step * 0.05;
        }
        // Liquid light: the dye is a glowing medium in its own right, so the
        // space BETWEEN the bodies carries colour and the room reads as full
        // of something rather than as objects in a void.
        //
        // Integrated WITH extinction, not as a plain sum. A plain sum has no
        // upper bound - a long ray through a well-inked region just keeps
        // adding until the frame is one flat colour, which is exactly what it
        // did - whereas ink that absorbs what is behind it is self-limiting,
        // and gives depth for free: the near side of a cloud of dye is
        // brighter than the far side, so the medium has a shape.
        if (uLiquid > 0.001) {
            // Sampled at a jittered point WITHIN this segment rather than at
            // its near end. Every pixel otherwise samples the medium at the
            // same depths and the integral quantizes into concentric shells -
            // it looked like contour lines drawn on the fog. The segment is
            // now bounded by uMaxStep, so this is a stratified sample of the
            // stretch being integrated; before, it was an offset of a whole
            // open-space step, which put the sample tens of world units away
            // from the piece of ray it was supposed to represent.
            vec3 ink = dyeAt(p + rd * (jitter * step));
            float dens = uLiquid * dot(ink, vec3(0.333));
            // Integrated in CLOSED FORM over the segment, not as emission
            // times length. For a medium that is constant along the segment
            // the transport equation has an exact solution, and the two forms
            // agree to first order in the step - but the sum form only
            // approaches the emission/extinction ceiling in the limit of many
            // small steps, and this march deliberately takes long ones
            // wherever there is nothing to hit. The closed form carries that
            // ceiling at ANY step size, which is what makes the medium's
            // brightness a property of the ink rather than of how far the
            // geometry happened to let the ray jump.
            float absorbed = 1.0 - exp(-dens * step * LIQUID_EXTINCTION);
            glow += ink * trans * (LIQUID_EMISSION / LIQUID_EXTINCTION) * absorbed;
            trans *= 1.0 - absorbed;
        }
        t += step;
        if (t > uFar) break;
    }

    vec3 sky = styleSky(rd, chrysanthemum(rd));
    vec3 col;

    if (hitT > 0.0) {
        vec3 p = ro + rd * hitT;
        float e = max(uHitEps * hitT, 6e-4);
        // hitHue/hitTrap are captured BEFORE these calls: calcNormal and
        // calcAO both go through map(), which overwrites the globals.
        vec3 n = calcNormal(p, e);
        float ao = calcAO(p, n, max(hitT * 0.02, 0.02));
        // Cheap second occlusion term: a ray that needed most of its budget
        // was crawling through folded geometry, and that is exactly where the
        // creases are.
        ao *= 1.0 - 0.35 * travelled;

        vec3 key = normalize(vec3(0.55, 0.75, -0.38));
        vec3 fill = normalize(vec3(-0.60, -0.15, 0.70));
        float dif = clamp(dot(n, key), 0.0, 1.0);
        float bounce = clamp(dot(n, fill), 0.0, 1.0);
        float fres = pow(1.0 - clamp(dot(n, -rd), 0.0, 1.0), 3.0);

        // The orbit trap bands the body into nested shells of different hue -
        // the reason a sphere packing reads as jewellery and not as grey foam.
        float band = log(max(hitTrap, 1e-6)) * 0.16;
        vec3 body = palette(hitHue + band * uTrapColor, 0.88);
        vec3 rim = palette(hitHue + band * uTrapColor + 0.34, 0.72);
        // Per-substyle colour identity, for all eleven rather than the three
        // that used to hardcode RGB constants here. The accent is an OFFSET
        // from the user's base hue (uStyleTint.x, in turns), so Reliquary is
        // always a warmer metal than the palette and Foam always a paler
        // pearl of it, whatever palette the user chose - identity without
        // overriding their colour controls. Amount 0 (the Original) leaves
        // the palette untouched.
        vec3 accent = hsv2rgb(vec3(fract(uBaseHue + uStyleTint.x), uStyleTint.y, 1.0));
        body = mix(body, accent, uStyleTint.z * 0.45);
        rim = mix(rim, accent, uStyleTint.z * 0.6);

        // Flow-aligned combing. Ridges running ALONG the medium's own flow are
        // the single most recognisable mark in the reference paintings - every
        // surface in them is combed, and the combing follows the current
        // rather than the geometry. Measured on the flow direction at the
        // surface, so it swims when the medium moves and stands still when it
        // does not.
        //
        // Reads flowAt, not meltAt: this control marks the surface, it does
        // not move it, so it has no business being multiplied by how far the
        // melt is allowed to push. Through meltAt it was an exact no-op at
        // Melt 0 - the whole slider did nothing until a different one was up.
        float comb = 0.0;
        if (uRidges > 0.001) {
            vec3 flow = flowAt(p);
            float speed = length(flow);
            if (speed > 1e-5) {
                // Across the flow, not along it: a wave measured along its own
                // direction of travel is invisible, because the whole pattern
                // moves with it.
                vec3 across = normalize(cross(flow / speed, n));
                comb = sin(dot(p, across) * 26.0 - uTime * 0.6) * clamp(speed * 6.0, 0.0, 1.0);
            }
        }
        // Comb the hue as well as the light: a purely tonal ridge reads as
        // corrugation, an iridescent one reads as a wet surface.
        body = mix(body, palette(hitHue + band * uTrapColor + 0.12 * comb, 0.88), uRidges * 0.5);

        col = body * (0.09 + 0.72 * dif + 0.30 * bounce) * ao;
        col *= 1.0 + uRidges * 0.30 * comb;
        // The neon outline every one of these paintings has: light gathering
        // along the silhouette, warmed a little on beats.
        col += rim * fres * uNeon * (1.5 + 0.5 * clamp(uBeat, 0.0, 1.0));
        // Specular, on the key light only.
        vec3 h = normalize(key - rd);
        col += vec3(1.0) * pow(clamp(dot(n, h), 0.0, 1.0), 42.0) * 0.55 * ao;
        // Translucency: how much of the body is behind this point. Thin edges
        // and thin filigree light up; thick cores stay dark.
        float thin = clamp(map(p - n * 0.12) / 0.12, 0.0, 1.0);
        col += body * thin * min(hitGlow, 2.5) * 0.16;

        // The stain: dye the medium has carried over this surface lights it.
        // Added rather than mixed, and lit by the same key - ink that ignored
        // the lighting sat ON the body like a decal instead of being wet on it.
        if (uStain > 0.001) {
            col += dyeAt(p) * uStain * (0.30 + 0.70 * dif) * ao;
        }

        // Into the haze with distance, so depth reads and the far bodies sit
        // behind the near ones rather than beside them. The haze is the DARK
        // of the void plus whatever filigree is behind - not the filigree
        // alone, which would make distance read as brightening.
        float fog = 1.0 - exp(-hitT * uHaze * 0.085);
        col = mix(col, sky * 0.7 + vec3(0.008, 0.006, 0.018), fog);
    } else {
        col = sky;
    }

    // Whatever the ray passed through occludes what it reached: the ink in
    // front of a body dims it, which is what puts the medium IN FRONT rather
    // than making it a wash laid over the top of a finished picture.
    col *= trans;
    col += glow * uGlow * 0.30;

    // Final family signatures, intentionally subtle enough that the existing
    // controls and palette remain recognisable across the whole collection.
    if (uStyle == 4) { // Cortex: fine neural ridges.
        float nerve = pow(0.5 + 0.5 * sin((uv.x + uv.y) * 34.0 + sky.r * 9.0), 12.0);
        col += palette(uv.x * 0.08 + uv.y * 0.05, 0.7) * nerve * 0.08;
    } else if (uStyle == 6) { // Moire: interference contrast.
        float rings = 0.72 + 0.28 * sin(length(uv) * 46.0 + atan(uv.y, uv.x) * 9.0);
        col *= rings;
    } else if (uStyle == 8) { // Dustskin: granular, treble-lit surface.
        float grain = hash31(vec3(gl_FragCoord.xy, floor(uTime * 18.0)));
        col *= 0.86 + 0.24 * grain;
        col += vec3(1.0) * smoothstep(0.985, 1.0, grain) * 0.12 * clamp(uTreble, 0.0, 1.5);
    } else if (uStyle == 9) { // Plume: lift the participating medium.
        col += sky * 0.16 * (0.4 + 0.6 * clamp(uEnergy, 0.0, 1.2));
    } else if (uStyle == 10) { // Resonant Wormhole: a slow modal breathing pulse.
        col *= 0.9 + 0.1 * sin(uTime * 0.33 + length(uv) * 7.0);
    }

    // Sum of three additive layers, HDR by construction. Clipping it would
    // flatten every rim and every core into the same white, so it is tone
    // mapped instead. Energy leans on the exposure only gently: this is a
    // full-screen brightness term, and it must stay far away from anything
    // that could read as a flash (see VisualSafety).
    float exposure = uExposure * (0.88 + 0.24 * clamp(uEnergy, 0.0, 1.2));
    col = vec3(1.0) - exp(-max(col, vec3(0.0)) * exposure);

    // A gentle vignette: these compositions all fall off into the dark at the
    // corners, and it also hides the far plane.
    float vig = 1.0 - 0.28 * dot(uv, uv) * 0.25;
    fragColor = vec4(col * clamp(vig, 0.0, 1.0), 1.0);
}
