#version 300 es
// Velocity-form heightfield wave update with damping, per RippleMath's
// headless-gate-verified CPU mirror (lockstep - change both together):
//   v += c^2*dt*laplacian(h)/dx^2;  v *= damping;  h = (h + v*dt) * heightDecay
// uK packs c^2*dt/dx^2. Clamped-edge boundary via the same half-texel
// clamp idiom as fluid_pressure_frag (Neumann: edge reflects, no wrap).
//
// uHeightDecay is not cosmetic. WITHOUT it the surface only ever GAINS: the
// Laplacian sums to zero over a Neumann grid, so the wave step conserves the
// mean of h exactly, every drop adds positive volume, and nothing takes any
// back. Velocity damping drains the ripples' oscillation but leaves that
// accumulated offset behind, so a pool under a full track climbs until it
// pins against the +/-8 rail, the gradient flattens and the style freezes
// into a saturated sheet - the "it only ever adds more, it never removes
// them" report. Draining h itself is what gives a drop a LIFETIME.
//
// TWO FUTURE-STYLE FEATURES ARE GATED OFF IN HERE, and both gates are the
// number zero. That choice is the whole design. This pass runs for WATER and
// for the renderer-owned ripple overlay, and the overlay rides on top of
// EVERY style, so a change here that is merely "close enough" is a change to
// the entire app. GL ES pins the default - "all active uniform variables
// defined in a program object are initialized to 0 when the program object is
// linked successfully" (glUniform, OpenGL ES 3.0) - and RippleSim uploads
// neither uniform, so every caller that exists today gets exactly the
// arithmetic it got before: at uStencil9 = 0 the branch below evaluates the
// same expression it always has, character for character, and at
// uVesselInvR2 = 0 the vessel factor is exactly 1.0, which multiplies every
// float back to itself. Neutral by construction rather than by measurement.
// The first style that wants either one uploads it; nothing shipping can.
precision highp float;
// GLSL ES 3.00 defaults fragment sampler2D to LOWP (range [-2,2), ~8
// fraction bits). Half-float height/velocity values exceed that; on GPUs
// honoring sampler precision (Mali) every read clamped and quantized.
precision highp sampler2D;
// vSim (sim space: y in [-1,1], x in [-aspect,aspect]) is what the vessel is
// round IN. It already comes out of fluid_base_vert - ripple_splat_frag
// places its drops with it - and RippleSim already uploads the uAspect that
// builds it to every one of its programs, so reading it here adds no uniform
// and no vertex work.
in vec2 vUv; in vec2 vSim; in vec2 vL; in vec2 vR; in vec2 vT; in vec2 vB;
uniform sampler2D uHeight;   // R = height, G = velocity
uniform float uK;            // c^2 * dt / dx^2
uniform float uDt;
uniform float uDamping;      // per-substep velocity decay factor
uniform float uHeightDecay;  // per-substep height decay (drains the mean)
uniform highp vec2 uInvRes;

// 0 = the shipping 5-point Laplacian; above 0.5 = the 9-point isotropic one.
// A BRANCH rather than a blend of the two, because the four diagonal taps are
// four extra dependent texture fetches per texel per substep - up to six
// substeps a frame on a 384-short-side grid - and the overlay makes every
// style in the app pay for this pass. The condition is a uniform, so it is
// coherent across the whole draw and no wavefront can diverge on it: the off
// path costs one scalar compare and never issues the fetches at all, where a
// mix() of both stencils would issue them forever, on styles that will never
// ask for them.
uniform float uStencil9;

// A circular Dirichlet vessel, given as the RECIPROCAL of its SQUARED radius
// in sim units. The encoding is not a micro-optimization, it is what makes
// the gate safe: "no vessel" has to BE the number zero, because zero is the
// number an uploaded-by-nobody uniform holds, and under a plain-radius
// encoding zero would instead mean a vessel of no size and would erase the
// pool on every style at once. Reciprocal-squared also keeps the test to one
// dot and one compare, with no divide and no sqrt per texel.
//
// Gated by arithmetic rather than by a branch, unlike uStencil9 above: there
// are no texture fetches to skip here, so a basic block would buy nothing,
// and multiplying by an exact 1.0 is the stronger neutrality claim - it holds
// however a driver reassociates the expression, because x * 1.0 is x for
// every finite float, both zeroes and every NaN.
uniform float uVesselInvR2;

out vec4 fragColor;

float sampleH(vec2 c) {
    vec2 cc = clamp(c, uInvRes * 0.5, 1.0 - uInvRes * 0.5);
    return texture(uHeight, cc).x;
}

void main() {
    vec2 hv = texture(uHeight, vUv).xy;
    float lap;
    if (uStencil9 > 0.5) {
        // The 9-point isotropic stencil, (4(L+R+T+B) + (TL+TR+BL+BR) - 20C)/6.
        // WHY it exists: the 5-point stencil's leading truncation error is
        // (dx^2/12)(d4h/dx4 + d4h/dy4), and that pair is not invariant under
        // rotation - it is largest along the axes and smallest on the
        // diagonals, so the discrete wave runs measurably FASTER diagonally
        // than axially and an expanding circular front becomes a rounded
        // square. Nobody notices that on a pool of overlapping drops; on a
        // bounded circular drumhead it is the difference between a round
        // figure and a polygonal one, and under a caustic - which
        // differentiates the height field a second time - the axis bias is
        // amplified into cross-shaped artefacts that read as a bug. The
        // 9-point weights cancel that anisotropic pair exactly and leave
        // (dx^2/12) times the biharmonic operator, which IS invariant under
        // rotation, so what is left over is round.
        //
        // The /6 is load-bearing, not tidiness: it normalizes this stencil
        // into the same units as the 5-point one, so uK, the CFL clamp in
        // RippleMath.cflClampedDt and the per-substep damping calibration all
        // keep meaning exactly what they meant. The weights still sum to zero
        // (4*4 + 4*1 - 20 = 0), so the Neumann mean-conservation that
        // uHeightDecay exists to drain is unchanged; and the stencil's
        // spectral radius drops from 8 to 32/6, so the existing 0.7 CFL clamp
        // becomes MORE conservative here rather than tighter.
        vec2 d = uInvRes;
        float face = sampleH(vL) + sampleH(vR) + sampleH(vT) + sampleH(vB);
        float diag = sampleH(vUv + d) + sampleH(vUv - d) +
                     sampleH(vUv + vec2(-d.x, d.y)) + sampleH(vUv + vec2(d.x, -d.y));
        lap = (4.0 * face + diag - 20.0 * hv.x) / 6.0;
    } else {
        lap = sampleH(vL) + sampleH(vR) + sampleH(vT) + sampleH(vB) - 4.0 * hv.x;
    }
    // The vessel rim is a NODE, not a wall: pinning h to zero at and beyond
    // the radius is a Dirichlet condition, so a front that reaches it reflects
    // INVERTED - which is what a clamped drumhead does, and what the grid's
    // own square edge (a Neumann antinode, reflecting without inversion) does
    // not. Applied to this pass's OUTPUT, so the exterior cells the neighbour
    // taps read are already zero and are the ghost cells the condition needs.
    // A drop splatted outside the vessel therefore survives exactly one
    // Laplacian evaluation before it is erased - which is a reason for a
    // consumer not to aim drops outside its own rim, not a reason to mask the
    // taps as well and charge every style for it.
    float vessel = 1.0 - step(1.0, dot(vSim, vSim) * uVesselInvR2);
    float v = (hv.y + uK * lap) * uDamping * vessel;
    // MAX_HEIGHT rail (mirrored in RippleMath.waveStep): a burst of drops can
    // still out-pace the decay for a moment; never let half-floats blow up.
    float h = clamp((hv.x + v * uDt) * uHeightDecay, -8.0, 8.0) * vessel;
    fragColor = vec4(h, v, 0.0, 1.0);
}
