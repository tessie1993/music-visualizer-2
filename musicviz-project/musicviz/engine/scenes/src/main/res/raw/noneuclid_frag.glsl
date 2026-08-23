#version 300 es
precision highp float;
// GLSL ES 3.00 defaults fragment sampler2D to LOWP (range [-2,2), ~8
// fraction bits). uAudioTex is R32F; on GPUs honoring sampler precision
// (Mali) every read is clamped and quantized.
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

// The three lines every fragment style carries, in this order: the uniform
// block/aband/awave/view() first, then the palette, then grade() - which calls
// pal() and so must come after it.
//#include lib_scene_uniforms
//#include lib_palette
//#include lib_scene_grade
// The 3D toolkit and the touch helpers, already wired so a style author never
// has to edit this header. Both are pure functions over things declared above;
// an unused one is dropped by the linker and costs nothing.
//#include lib_sdf3
//#include lib_touch

// NONEUCLID - space turning inside-out through a point.
//
// ---- what is actually being drawn -----------------------------------------
//
// One small prototype - a cubic lattice of struts with a bead at every node -
// seen through a chain of alternating BOX FOLDS and SPHERE INVERSIONS. That
// chain is the Kleinian/Apollonian construction: the box fold is a reflection
// (a translation generator's mirror), the sphere fold is inversion in a sphere
// (p -> p * r2/dot(p,p)), and the group they generate has a limit set that
// nests forever. The lattice is the only thing with any shape in this file;
// everything the eye reads as structure is what the group does to it.
//
// Inversion is the transform that swaps the inside of a sphere with the whole
// of the rest of space, which is why the picture cannot be read as a Euclidean
// object seen from an angle: the struts are straight in the folded frame and
// arrive on screen as ARCS, and their spacing changes as they approach a pole
// instead of foreshortening. That is the entire visual signature, and it is
// why the prototype is a lattice of straight lines rather than something that
// is already curved - a curved prototype would hide the transform.
//
// ---- why the estimate is safe to march ------------------------------------
//
// Every step of the chain is either an isometry (the box fold, the fold-basis
// rotation and offset, the world rotation) or a CONFORMAL map with a known
// scalar local scale factor (the two inversions). So the composition is
// conformal with scale s = product of those factors, and
//
//     distance in world units ~= lattice(folded point) / s
//
// which is the same accounting hyperspace_frag's deGasket/deCoral do. Two
// details keep it honest:
//
// - both inversions are FLOORED at a minimum radius. Below that floor the map
//   is a plain uniform scale, whose second derivative is zero - so the region
//   where the conformal pullback would be least accurate is exactly the region
//   where it becomes exact. Without the floor the reciprocal returns inf at
//   the pole and s goes to NaN, and one NaN distance is not a bad pixel: the
//   ray never terminates and the frame time collapses.
// - s is capped at 1e30. Ten inversions at the floor multiply to well past
//   float32 max, and an infinite s makes the estimate 0/inf = 0, i.e. a
//   surface reported where there is none. An underestimate is safe; the march
//   just creeps and the epsilon catches it.
//
// The march still steps a fraction of the estimate (STEP_SCALE), because a
// conformal pullback is only first-order and two nested inversion families
// leave more curvature between the floors than one does.
//
// ---- touch: the finger IS the pole ----------------------------------------
//
// There is always a global inversion pole in the scene, drifting slowly on its
// own so an untouched frame still has a lens of inside-out space wandering
// through the lattice. A finger takes it over: the pole eases to the touch
// point, so the world turns inside out about wherever you put your hand and
// dragging drags the singularity through the structure. Two fingers set the
// pole's radius (pinch the bubble open and shut), three or more rotate the
// fold basis. Every one of those terms is exactly zero when nothing is being
// touched, so an untouched frame is bit-identical to one from a build without
// the touch uniforms.

// ---- caps ------------------------------------------------------------------
//
// Compile-time bounds, in the shape lib_sdf3's RAYMARCH note requires: the
// bound is what the driver unrolls against, the break is what makes the common
// ray cheap. MAX_STEPS is MarchBudget.MAX_STEPS, the ceiling uSteps is handed
// out of; MAX_FOLDS is this style's own, and the runtime fold count breaks on
// gIters below it.
#define MAX_STEPS 128
#define MAX_FOLDS 10

const float TAU = 6.2831853;

// ---- camera ----------------------------------------------------------------
//
// The eye sits at the origin and looks down +z, so the ray frame IS the frame
// lib_touch documents for a screen anchor: x and y are the screen axes in
// view() units, z runs along the view direction. That is what lets a 2D finger
// position become a 3D pole with one divide (see gPole in main()), and it is
// the reason the camera is built this way round rather than as a free-flying
// eye with a basis matrix.
//
// FOCAL 1.6 is a 64-degree vertical field. Together with the distance and
// radius below the structure subtends about 88% of the half-height at the
// default Zoom - full enough to be the subject, with enough margin that the
// silhouette reads as a silhouette instead of being cropped by the frame.
const float FOCAL = 1.6;
/** Eye-to-structure distance. */
const float CAM_DIST = 4.05;
/** Radius of the ball the whole construction is clipped to. */
const float BALL_R = 1.95;
/**
 * Gate width on the bounding sphere.
 *
 * A gate at exactly zero makes the march creep: the step lands on the sphere,
 * the bound is zero, and the ray never gets inside. Same reason
 * hyperspace_frag carries uBoundMargin.
 */
const float BOUND_MARGIN = 0.06;
/** Nothing past this is drawn; the ball ends at CAM_DIST + BALL_R = 6.0. */
const float FAR = 7.2;
/**
 * Fraction of the estimate a step takes.
 *
 * Lower than hyperspace's 0.82 because there are TWO inversion families here
 * (the pole and the chain) and the conformal pullback is first-order: between
 * the radius floors the curvature term is real, and a ray stepping the full
 * estimate grazes through the thin struts, which shows up as sparkle on the
 * arcs rather than as anything that reads like an overshoot.
 */
const float STEP_SCALE = 0.72;
/** Relative surface epsilon. Constant epsilon shimmers far away and wastes
 *  steps up close, so it grows with the distance marched. */
const float HIT_EPS = 9.0e-4;

// ---- the prototype ---------------------------------------------------------
//
// Cell period is 1.0 by construction (fract), so every radius below is read as
// a fraction of a cell. Struts at 0.082 leave roughly five sixths of every
// face open: the gaps are what let the eye see three or four levels of the
// nesting at once, and a fatter strut closes the structure into a solid that
// could be any fractal at all.
const float CELL_STRUT = 0.082;
const float CELL_BEAD = 0.155;
/** Weld radius at the nodes. Small enough that the joint reads as a joint;
 *  smin's blend is not associative, so bead-then-struts is the shape. */
const float CELL_WELD = 0.07;

// ---- the folds -------------------------------------------------------------
/** Half-size of the box fold. 1.0 puts the mirror planes on the unit cube. */
const float BOX_FOLD = 1.0;
/** Radius^2 of the chain's sphere fold, before bass. */
const float INV_R2 = 0.96;
/**
 * Floor on the chain's inversion radius^2.
 *
 * 0.28 caps one fold's magnification at 0.96/0.28 = 3.4, so ten of them reach
 * about 2e5 rather than something that has to be defended against float32
 * overflow every iteration. It is also the radius inside which the map becomes
 * a pure uniform scale - see the header note on why that is where the estimate
 * needs it most.
 */
const float INV_MIN_R2 = 0.28;
/** Radius^2 of the global pole, before bass and pinch. */
const float POLE_R2 = 0.62;
/** Floor on the pole, same job as INV_MIN_R2. Peak magnification 0.62/0.055 =
 *  11, which is what makes the bubble read as a different world rather than as
 *  a dent. */
const float POLE_MIN_R2 = 0.055;

// ---- look ------------------------------------------------------------------
/** How tightly the ray-integrated glow hugs the surface. */
const float GLOW_TIGHT = 9.0;
const float FOG_RATE = 0.115;
const float EXPOSURE = 1.30;

// ---- per-frame constants, and the two orbit traps --------------------------
//
// Everything here is computed once in main() and read by map(); GLSL ES has no
// way to hand a closure to a distance function, so this is the same file-scope
// idiom hyperspace_frag uses for gTrap/gHue.
/** Centre of the bounding ball, in the camera frame. */
vec3 gCenter;
float gRadius;
/** The inversion pole, in the camera frame. */
vec3 gPole;
float gPoleR2;
/** Chain parameters. */
float gInvR2;
float gIters;
mat3 gFoldRot;
vec3 gFoldOff;
mat3 gWorldRot;
float gBead;
/**
 * The orbit traps: the closest the folded point ever came to the origin and to
 * the y axis, both kept SQUARED so the loop never pays a sqrt. A fractal
 * surface coloured by depth alone reads as noise; coloured by what its orbit
 * did it reads as nested shells, which is the difference between a texture and
 * a structure. Written by map(), so anything that calls map() again - the
 * normal, the occlusion - overwrites them: capture before shading.
 */
float gTrapO;
float gTrapA;

/**
 * The prototype: an infinite cubic lattice of struts with a bead at each node.
 *
 * Exact and Lipschitz-1, which matters because it is the only thing dividing
 * by the accumulated scale - an overestimate here is multiplied by s and walks
 * the ray through the whole construction. It is exact because each family is
 * evaluated on the cell-relative coordinate and every radius is under half a
 * cell, so the nearest member of a family is always the one in your own cell:
 * a tube along x depends only on (y,z), so `length(c.yz) - r` is the distance
 * to the whole infinite family and not just to a neighbour.
 *
 * fract() rather than round(): round()'s behaviour at exactly .5 is not pinned
 * down, and a half-cell plane that resolves one way on one driver and the
 * other way on another is a seam through the middle of every cell.
 */
float lattice(vec3 p) {
    vec3 c = fract(p + 0.5) - 0.5;
    float bead = length(c) - gBead;
    float struts = min(length(c.yz), min(length(c.xz), length(c.xy))) - CELL_STRUT;
    return smin(bead, struts, CELL_WELD);
}

/**
 * The scene: the Kleinian chain, clipped to its bounding ball.
 *
 * Order is deliberate. The pole inversion runs in the CAMERA frame, before the
 * world rotation, so the singularity stays under the finger while the
 * structure turns behind it - inverting after the rotation would drag the pole
 * around the screen once every world revolution.
 */
float map(vec3 p) {
    // Outside the ball the distance to the ball is already a valid lower bound
    // on the distance to anything in it, so the fold chain is never touched
    // and the empty half of the frame costs one length() per step.
    float bound = sdSphere(p - gCenter, gRadius);
    if (bound > BOUND_MARGIN) {
        // The traps still have to be defined: main() reads them after every
        // map() call to colour the ray-integrated glow, and a stale pair would
        // paint the void in the colour of whatever the ray last passed.
        gTrapO = 1.0;
        gTrapA = 1.0;
        return bound;
    }

    // The running conformal scale. Both inversions multiply into it and the
    // final estimate is divided by it; see the header.
    float s = 1.0;

    // ---- the global pole: the inside of this sphere IS the outside ---------
    //
    // Only points INSIDE the pole sphere are moved. That is what makes it read
    // as a lens set into the structure rather than as the whole frame sliding:
    // the lattice around the bubble stays put, so the eye has an undistorted
    // reference to measure the distortion against, and the boundary is
    // continuous because k -> 1 exactly as r2 -> gPoleR2 from below.
    vec3 v = p - gPole;
    float pr2 = dot(v, v);
    float pk = pr2 < gPoleR2 ? gPoleR2 / max(pr2, POLE_MIN_R2) : 1.0;
    vec3 q = gPole + v * pk;
    s *= pk;

    // Into the structure's own frame. A rotation and a translation, so the
    // bounding sphere tested above is still the sphere this is inside of.
    q = gWorldRot * (q - gCenter);

    gTrapO = 1.0e9;
    gTrapA = 1.0e9;
    for (int i = 0; i < MAX_FOLDS; i++) {
        if (float(i) >= gIters) break;
        // Box fold: reflect anything outside the cube back in. A reflection,
        // so it costs the scale factor nothing. Written as clamp*2 - p rather
        // than as a branch because the branch is non-uniform across the quad
        // and the arithmetic form is the same three operations either way.
        q = clamp(q, -BOX_FOLD, BOX_FOLD) * 2.0 - q;

        float r2 = dot(q, q);
        // The traps are read HERE, between the two folds, because this is the
        // one point in the iteration where the orbit is in the frame the group
        // is defined in - after the inversion it has been thrown outward and
        // the same shell means a different thing every iteration.
        gTrapO = min(gTrapO, r2);
        gTrapA = min(gTrapA, dot(q.xz, q.xz));

        // Sphere fold. Three regimes, continuous across both joins: identity
        // outside, inversion between the radii, uniform scale inside the
        // floor.
        float k = r2 < gInvR2 ? gInvR2 / max(r2, INV_MIN_R2) : 1.0;
        q *= k;
        s = min(s * k, 1.0e30);

        // Rotate the fold basis and step it. Both isometries, so the whole
        // chain's scale factor is still just the product of the inversions.
        // The rotation is what stops the group from being a lattice of its own
        // - with an axis-aligned basis every level lands on the previous one
        // and the nesting reads as one repeated cell instead of as depth.
        q = gFoldRot * q + gFoldOff;
    }

    // Pull the prototype's distance back through the accumulated scale, then
    // intersect with the ball. The chain describes an UNBOUNDED set - the
    // lattice is infinite and the folds do not confine a far-away ray - so
    // without the intersection the structure streaks off across the whole
    // frame and there is no silhouette and no space to see it in.
    return max(lattice(q) / s, bound);
}

/** Tetrahedral normal: four map() taps rather than a central difference's six,
 *  and symmetric, so a flat face reads flat. */
vec3 normalAt(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(
        k.xyy * map(p + k.xyy * eps) + k.yyx * map(p + k.yyx * eps) +
            k.yxy * map(p + k.yxy * eps) + k.xxx * map(p + k.xxx * eps)
    );
}

/**
 * Two-tap ambient occlusion, written out rather than looped.
 *
 * Each tap is a whole map() - a whole fold chain - so a third tap is a real
 * cost for a term the eye reads as "the creases are darker". Two is enough to
 * seat the struts into their nodes, which is the only place this construction
 * has creases worth seating.
 */
float occlusion(vec3 p, vec3 n, float h) {
    float o1 = (h - map(p + n * h)) / h;
    float o2 = (2.0 * h - map(p + n * 2.0 * h)) / (2.0 * h);
    return clamp(1.0 - 0.75 * (o1 + 0.55 * o2), 0.0, 1.0);
}

/**
 * The void behind the structure.
 *
 * Inverted as well, about the pole's own screen position, so the background
 * gradient's iso-lines are circles through the pole instead of straight bands
 * - the empty part of the frame agrees with the geometry about what straight
 * means. Deliberately dim: this is a full-screen term, and a full-screen term
 * that moved with the music is exactly the shape of thing the photosensitivity
 * budget exists to keep out.
 */
vec3 sky(vec3 rd, vec2 poleScreen) {
    // Project the direction onto the focal plane. The floor on rd.z keeps a
    // grazing ray (the Zoom control can widen the field a long way) from
    // sending this to infinity.
    vec2 sp = rd.xy / max(rd.z, 0.2) - poleScreen;
    // Same floored reciprocal as the geometry, for the same reason: the raw
    // one is inf at the pole, and sin() of inf is not a gradient.
    vec2 inv = sp / max(dot(sp, sp), 0.06);
    float f = 0.5 + 0.5 * sin(inv.x * 1.2 + uTime * 0.061) * cos(inv.y * 1.1 - uTime * 0.047);
    return pal(0.62 + f * 0.16) * (0.030 + 0.045 * f + 0.05 * clamp(uEnergy, 0.0, 1.5));
}

void main() {
    // view() first, so Zoom, Rotation, Drift, Kaleido, Tiling, Pixelate,
    // Shake, Twist, Warp, Ripple, Morph and the beat pulse all reach this
    // style the same way they reach every other one. For a raymarcher they act
    // on the RAY DIRECTION field, which is the honest 3D reading of each: Zoom
    // becomes focal length, Kaleido becomes a mirrored lens, Tiling becomes a
    // wall of copies of the view.
    vec2 uv = view();

    // Audio, normalized out of the 0..1.5 contract and clamped, so every gain
    // below is read against a 0..1 scale.
    float bassN = clamp(uBass, 0.0, 1.5) / 1.5;
    float midN = clamp(uMid, 0.0, 1.5) / 1.5;
    float trebN = clamp(uTreble, 0.0, 1.5) / 1.5;
    float energyN = clamp(uEnergy, 0.0, 1.5) / 1.5;

    // The house beat idiom: the squared envelope times the phase bump, so it
    // is a spike ON the beat and silent between hits even though the phase
    // clock free-runs through silence.
    float beatEnv = clamp(uBeat, 0.0, 1.0);
    float beatBump = pow(0.5 + 0.5 * cos(TAU * uBeatPhase), 2.0);
    float beatHit = beatEnv * beatEnv * beatBump;

    // ---- touch -----------------------------------------------------------
    //
    // Every term here is exactly zero on an untouched frame: touchStrength()
    // returns a literal 0, uTouchAxis and uTouchSpin are written as literal
    // zeros, and mix(a, b, 0.0) is a. So the frame is bit-identical to one
    // from a build with no touch at all.
    //
    // The ease is on the anchor's AGE rather than on a filter, because a
    // fragment shader has no state to filter with. TouchField already ramps
    // the strength down on release, so this only has to handle the other end:
    // 0.18 s is long enough that the pole slides into place instead of
    // teleporting, and short enough that it still feels like a direct grab.
    float grab = touchStrength() * smoothstep(0.0, 0.18, uTouchAnchor.w);
    // Two fingers: the separation opens the pole. Zero-length axis with fewer
    // than two down, so this contributes an exact 1.0 to the product below.
    float pinch = clamp(length(uTouchAxis) * 0.62, 0.0, 1.0);
    // Three or more: the swirl turns the fold basis. uTouchSpin is a RATE and
    // this uses it as a bounded angular offset rather than integrating it -
    // there is nowhere in a fragment shader to keep the integral, and a
    // stateless one would drift and never come back. As an offset the basis
    // twists while the fingers turn and springs back when they stop, which is
    // the gesture the user is making anyway.
    float spin = clamp(uTouchSpin * 0.55, -0.9, 0.9);

    // ---- the frame's geometry --------------------------------------------
    //
    // Bass pushes the eye in. A camera push is a continuous quantity, so the
    // gain is small: 7% of the standoff at a full-scale low end, which reads
    // as the structure leaning toward you and cannot read as a cut.
    float camZ = CAM_DIST - 0.30 * bassN;
    // A slow lateral wander so the structure is never quite centred. Rates are
    // chosen mutually irrational-ish (0.061 / 0.043) so the two axes do not
    // relock into a straight line every few seconds.
    gCenter = vec3(0.22 * sin(uTime * 0.061), 0.17 * sin(uTime * 0.043 + 1.1), camZ);
    gRadius = BALL_R;

    // The pole. Idle, it drifts on its own inside the ball, which is the
    // ambient motion this style has to have with the audio at zero: a lens of
    // inside-out space wandering through the lattice, plus the fold basis
    // turning below.
    vec3 drift = vec3(
        0.55 * sin(uTime * 0.071),
        0.44 * sin(uTime * 0.053 + 2.1),
        0.38 * sin(uTime * 0.037 + 4.2)
    );
    vec3 idlePole = gCenter + drift;
    // The finger's pole. touchAnchor() is in view() units, and a point at
    // depth z lands on screen at xy * FOCAL / z, so this is the inverse of
    // that projection at the structure's own depth: the pole sits exactly
    // under the fingertip.
    //
    // Exactly, that is, for the default view. touchAnchor() is deliberately
    // NOT pushed through view()'s stack (lib_touch says so), and view() is not
    // invertible in closed form once Kaleido, Tiling or Pixelate are folding
    // the plane - so with those on, the pole is placed where the finger is on
    // the SURFACE and the fold decides where that appears. Nothing else is
    // available without duplicating view(), and a second copy of view() is the
    // exact duplication lib_scene_uniforms exists to prevent.
    vec3 grabPole = vec3(touchAnchor() * camZ / FOCAL, camZ);
    gPole = mix(idlePole, grabPole, grab);
    // Bass opens the pole, the pinch opens it a lot more. Bass gain is held to
    // 30% because the pole's radius is GEOMETRY - it decides how much of the
    // frame the inside-out region covers - and there is no slew-limited
    // envelope in the shared uniform contract to lean on, so the coupling has
    // to be small enough that per-frame jitter stays under the eye's threshold.
    gPoleR2 = POLE_R2 * (1.0 + 0.30 * bassN + 0.85 * pinch);

    // The chain. Mids steer the fold planes: same argument as above about
    // gain, and mids are the steering band by convention.
    float foldAngle = uTime * 0.041 + midN * 0.45 + spin;
    gFoldRot = rotAxis(vec3(0.37, 0.78, -0.51), foldAngle);
    // The offset breathes slowly, so the group is never the same group twice
    // and the structure keeps reorganising with the audio at zero.
    gFoldOff = vec3(0.62, -0.24, 0.41) * (1.0 + 0.06 * sin(uTime * 0.029));
    gInvR2 = INV_R2 * (1.0 + 0.10 * bassN);
    gWorldRot = rotY(uTime * 0.083) * rotX(0.26 * sin(uTime * 0.037));
    gBead = CELL_BEAD + 0.030 * bassN;

    // Fold count. The base tracks the user's Detail through uSteps, because on
    // this style the per-STEP cost is the fold chain and a Detail control that
    // only shortened the march would leave the expensive half untouched. The
    // beat then steps it - the one discrete event in the style, and the only
    // thing uBeat is allowed to touch here. Two levels at most: an extra fold
    // refines the surface inside the silhouette it already has, so it reads as
    // the structure gaining a level rather than as the frame changing area,
    // which is the property the flash budget cares about.
    float detail = clamp((uSteps - 64.0) / 64.0, 0.0, 1.0);
    gIters = clamp(
        6.0 + floor(detail * 2.0 + 0.5) + floor(beatHit * clamp(uBeatResponse, 0.0, 1.0) * 2.99),
        4.0,
        float(MAX_FOLDS)
    );

    vec3 ro = vec3(0.0);
    vec3 rd = normalize(vec3(uv, FOCAL));

    // Per-pixel start offset. The glow below is an integral along the ray, and
    // an integral sampled at the same depths on every pixel quantizes into
    // concentric shells that read as contour lines. hash13 rather than
    // fract(sin(dot(...))): the sin hash degenerates into bands at mediump,
    // which GLSL ES 3.00 permits and Mali does. Seeded on the pixel only, not
    // on time - a temporally varying dither on a fullscreen pass is flicker,
    // and this app has a photosensitivity budget.
    float jitter = hash13(vec3(gl_FragCoord.xy, 7.0));
    float t = 0.35 + jitter * 0.03;

    float hitT = -1.0;
    float hitTrapO = 1.0;
    float hitTrapA = 1.0;
    // The ray-integrated glow: weight only, plus the traps at the ray's
    // closest approach. Colour is resolved ONCE after the loop instead of
    // calling pal() per step - pal() is a pair of cosines and, with a colour
    // map selected, a texture fetch, and 128 of those per pixel is the whole
    // frame budget for a term that is a soft halo.
    float glowSum = 0.0;
    float glowBest = 0.0;
    float glowTrapO = 1.0;
    float glowTrapA = 1.0;

    for (int i = 0; i < MAX_STEPS; i++) {
        if (float(i) >= uSteps) break;
        vec3 p = ro + rd * t;
        float d = map(p);
        // Epsilon grows with the distance marched: a constant one shimmers on
        // the far side of the ball and wastes steps on the near side.
        float eps = HIT_EPS * t + 3.5e-4;
        if (d < eps) {
            hitT = t;
            hitTrapO = gTrapO;
            hitTrapA = gTrapA;
            break;
        }
        // Everything near a surface leaks light. No step cap is needed on this
        // quadrature: the weight is exp(-d * GLOW_TIGHT), so the long steps
        // are exactly the ones that contribute nothing, and the steps that do
        // contribute are short because they are near the geometry.
        float step = max(d * STEP_SCALE, eps * 0.5);
        float w = exp(-d * GLOW_TIGHT) * step;
        glowSum += w;
        if (w > glowBest) {
            glowBest = w;
            glowTrapO = gTrapO;
            glowTrapA = gTrapA;
        }
        t += step;
        if (t > FAR) break;
    }

    vec2 poleScreen = gPole.xy * FOCAL / max(gPole.z, 0.5);
    vec3 background = sky(rd, poleScreen);
    vec3 col;

    if (hitT > 0.0) {
        vec3 p = ro + rd * hitT;
        float eps = max(HIT_EPS * hitT, 6.0e-4);
        // Captured above, because normalAt() and occlusion() both go through
        // map() and map() owns the traps.
        vec3 n = normalAt(p, eps);
        float ao = occlusion(p, n, clamp(0.03 + 0.012 * hitT, 0.03, 0.09));

        // The orbit trap, as a palette coordinate. Two traps rather than one:
        // the distance to the ORIGIN bands the surface into the nested shells
        // the group generates, and the distance to the AXIS cuts across those
        // shells, so two surfaces that happen to sit at the same depth in the
        // group are still told apart. One trap alone gave concentric rings
        // that read as a target rather than as a structure.
        float trapO = sqrt(hitTrapO);
        float trapA = sqrt(hitTrapA);
        float hue = 0.08 + trapO * 0.85 + trapA * 0.42;

        vec3 body = pal(hue);
        vec3 rim = pal(hue + 0.34);

        vec3 key = normalize(vec3(0.48, 0.72, -0.50));
        vec3 fill = normalize(vec3(-0.62, -0.18, 0.76));
        float dif = clamp(dot(n, key), 0.0, 1.0);
        float bounce = clamp(dot(n, fill), 0.0, 1.0);
        float fres = pow(1.0 - clamp(dot(n, -rd), 0.0, 1.0), 3.0);

        // The ambient term is a constant, not an audio term: silence has to
        // land on a lit structure, not on a black screen.
        col = body * (0.11 + 0.62 * dif + 0.26 * bounce) * ao;

        // The orbit-trap highlight - the filament along the surfaces whose
        // orbit hugged the axis. Treble sharpens it, which is the convention
        // (treble -> edge sharpness, fine detail, sparkle): at silence it is a
        // broad sheen over the arcs and at full treble it collapses onto the
        // ridges, so the same surface picks out its own edges as the top end
        // comes up. Exponent, not amplitude: sharpening an existing highlight
        // moves no area, where raising its amplitude would.
        float spark = pow(clamp(1.0 - trapA * 1.6, 0.0, 1.0), mix(5.0, 22.0, trebN));
        col += pal(hue + 0.5) * spark * (0.22 + 0.55 * trebN);

        // The rim: light gathering along the silhouette, which is where an
        // inverted lattice is most obviously not a Euclidean one - the
        // silhouette is made of arcs.
        col += rim * fres * (0.30 + 0.45 * energyN);
        col += vec3(1.0) * pow(clamp(dot(n, normalize(key - rd)), 0.0, 1.0),
            mix(24.0, 60.0, trebN)) * 0.30 * ao;

        // Into the void with distance, so the far side of the ball sits behind
        // the near side rather than beside it.
        col = mix(col, background, 1.0 - exp(-hitT * FOG_RATE));
    } else {
        col = background;
    }

    // The glow, coloured once. uEnergy is the density control by convention;
    // the floor keeps it present in silence, where it is the only thing giving
    // the gaps between the struts any depth.
    float glowHue = 0.08 + sqrt(glowTrapO) * 0.85 + sqrt(glowTrapA) * 0.42;
    col += pal(glowHue + 0.18) * glowSum * (0.16 + 0.34 * energyN);

    // The wake: a soft lift under every finger, live and still fading.
    // touchWake() is unbounded above by design (five fingers sum to five), so
    // it goes through 1 - exp(-x) before it is allowed near the frame - five
    // fingers brighten one spot a little more than one finger does, and never
    // by five times. Local and gradual, which is what keeps it out of the
    // flash budget. Exactly zero when nothing is touching.
    float wake = 1.0 - exp(-touchWake(uv));
    col += pal(0.30 + 0.25 * energyN) * wake * 0.16;

    // Three additive layers, so this is HDR by construction. Clipping would
    // flatten every rim and every filament into the same white; the tone map
    // keeps them apart and, more to the point, bounds the whole frame's
    // luminance whatever the music does.
    col = vec3(1.0) - exp(-max(col, vec3(0.0)) * EXPOSURE);

    // Vignette in SCREEN space, off vUv rather than off view()'s uv. Off uv it
    // would be a function of the Zoom control: zoomed out far enough, dot(uv,
    // uv) is large everywhere and the vignette would take the whole frame to
    // black.
    vec2 nd = vUv * 2.0 - 1.0;
    col *= 1.0 - 0.20 * dot(nd, nd) * 0.5;

    fragColor = vec4(grade(col), 1.0);
}
