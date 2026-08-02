#version 300 es
precision highp float;

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
// The five distance estimators below are the published constructions - see
// THIRD_PARTY_NOTICES for the technique references - written here from the
// mathematics rather than ported: fold space, then invert or scale it, and
// keep the running scale factor so the estimate can be pulled back to world
// units. That single shape is why five different fractals fit in one loop.
//
// Cost control, because eight distance-estimated fractals per step would not
// run on a phone: every body carries a bounding sphere, and outside it the ray
// steps by the distance to the sphere and the fractal is never touched. A ray
// therefore iterates the one or two bodies it is actually near.

in vec2 vUv;
out vec4 fragColor;

// Compile-time ceilings. The runtime budget (uSteps/uIters/uBulbIters) is a
// uniform so "Detail" can move without recompiling; these only bound the loop.
#define MAX_BLOOMS 8
#define MAX_STEPS 128
#define MAX_ITERS 14
#define MAX_BULB_ITERS 10

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
uniform float uTime;

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
        s *= k;
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
 * the end divided by the accumulated scale is still a valid estimate.
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
        // The axis the sponge treats differently: without this the fold closes
        // into a solid block and the terraces disappear.
        if (p.z < -1.0) p.z += 2.0;
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
        s *= k;
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
        float phi = atan(z.y, z.x) * power;
        dr = pow(r, power - 1.0) * power * dr + 1.0;
        float zr = pow(r, power);
        float st = sin(theta);
        z = zr * vec3(st * cos(phi), st * sin(phi), cos(theta)) + p;
    }
    // Floored: a point that never escaped has log(r) <= 0, and a zero step
    // stalls the march on the spot forever.
    return max(0.5 * log(max(r, 1.0001)) * r / max(dr, 1e-5), 2e-4);
}

/** Dispatch on the body's species ordinal (see HyperspaceMath.Species). */
float speciesDE(vec3 p, int species, float fold) {
    if (species == 0) return deGasket(p, fold);
    if (species == 1) return deTemple(p, fold);
    if (species == 2) return deJewel(p, fold);
    if (species == 3) return deCoral(p, fold);
    return deBulb(p, fold);
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
        // The body breathes: a slow wobble of its own fold constant, on its
        // own phase, so a body is never quite the same shape twice - and the
        // bass leans on it, gently and equally for every body.
        float fold = S.z * (1.0 + 0.04 * sin(L.z) + 0.02 * uBass);
        gT = 1e9;
        float df = speciesDE(q, int(S.x + 0.5), fold) * max(S.y, 1e-4);
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
 * The translation constant drifts, slowly and on three unrelated rates, so the
 * fabric keeps reorganising instead of standing still.
 */
vec3 chrysanthemum(vec3 rd) {
    if (uField <= 0.002) return vec3(0.0);
    // A high starting scale is what makes this a FABRIC rather than a handful
    // of blobs: the iteration's structure is at unit scale, so entering it far
    // from the origin puts many periods of it inside one degree of view.
    //
    // Compensated for the mirror: the kaleidoscope squeezes the whole screen
    // into one sector of directions, so the SAME field spread over 1/folds of
    // the angles it used to cover comes out that many times coarser - which is
    // how a filigree turned into flat washes the moment the fold came on.
    float squeeze = uMirror >= 0.5 ? max(uMirrorFolds, 2.0) * 0.5 : 1.0;
    vec3 p = rd * (4.6 + 0.7 * sin(uTime * 0.05)) * squeeze;
    vec3 c =
        vec3(0.84, 0.91, 0.72) +
            0.07 * vec3(sin(uTime * 0.043), cos(uTime * 0.037), sin(uTime * 0.029));
    // The CLOSEST approach of the orbit, not a sum over it. A sum washes the
    // whole sky to a pastel haze; the closest approach is an orbit trap, and
    // its narrow response is what leaves thin bright strands on black - the
    // difference between a filigree and a fog.
    //
    // Two traps, because the fabric has two scales: the distance to the origin
    // draws the knots, and the distance to the nearest axis plane draws the
    // threads running between them.
    float knot = 1e9;
    float thread = 1e9;
    float hue = 0.0;
    for (int i = 0; i < 12; i++) {
        p = abs(p) / max(dot(p, p), 1e-4) - c;
        float m = length(p);
        if (m < knot) {
            knot = m;
            hue = float(i) * 0.11 + m * 0.6;
        }
        thread = min(thread, min(min(abs(p.x), abs(p.y)), abs(p.z)));
    }
    float strands = exp(-knot * knot * 55.0) + 0.55 * exp(-thread * thread * 420.0);
    // Treble picks out the fine strands. Every factor here is well under a
    // brightness that could read as a flash, at any level (see VisualSafety).
    float sharpen = 1.0 + 0.35 * clamp(uTreble, 0.0, 1.2);
    return palette(hue, 0.82) * strands * uField * (0.20 + 0.13 * clamp(uEnergy, 0.0, 1.2)) * sharpen;
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

    vec3 sky = chrysanthemum(rd);
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
