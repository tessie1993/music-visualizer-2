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
uniform float uHitEps;
uniform float uBoundMargin;

// ---- look --------------------------------------------------------------
/** Continuous act position, 0..4. Only used for the mood, never for geometry. */
uniform float uAct;
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
    float d = 1e9;
    gAuraW = 0.0;
    gAuraH = 0.0;
    gSurface = 0.0;
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
        // Into the body's frame: rotate by ITS rotation, scale by ITS scale.
        vec3 q = (uBloomRot[i] * rel) / max(S.y, 1e-4);
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

    float t = 0.35;
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
        float step = d * 0.82;
        glow +=
            palette(gHue + log(max(gTrap, 1e-6)) * 0.14 * uTrapColor, 0.78) *
                gGlow * gSurface * exp(-d * 12.0) * step;
        if (gAuraW > 1e-4) {
            glow += palette(gAuraH / gAuraW, 0.80) * gAuraW * step * 0.05;
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

        col = body * (0.09 + 0.72 * dif + 0.30 * bounce) * ao;
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

        // Into the haze with distance, so depth reads and the far bodies sit
        // behind the near ones rather than beside them. The haze is the DARK
        // of the void plus whatever filigree is behind - not the filigree
        // alone, which would make distance read as brightening.
        float fog = 1.0 - exp(-hitT * uHaze * 0.085);
        col = mix(col, sky * 0.7 + vec3(0.008, 0.006, 0.018), fog);
    } else {
        col = sky;
    }

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
