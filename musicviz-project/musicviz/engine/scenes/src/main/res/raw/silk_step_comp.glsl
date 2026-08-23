#version 310 es
precision highp float;
precision highp int;
layout(local_size_x = %%LOCAL_SIZE_X%%, local_size_y = %%LOCAL_SIZE_Y%%) in;

// SILK - the field-advection pass, as a compute kernel.
//
// The compute-tier twin of silk_step_frag.glsl, and the TEMPLATE the other
// three field-sim families (Life, Acid, Myco) follow when their turn comes. The
// shape is the same for all four: one gather of the previous state through a
// per-family coordinate warp, one pointwise recipe on top, one store. Nothing
// in that shape needs a rasterizer, and a field sim that never rasterizes never
// binds an FBO, never resolves a tile and never interpolates a varying whose
// value is just the texel centre.
//
// What follows is silk_step_frag.glsl's body, unchanged except where the format
// forces a change - and every one of those places is marked. Keep the two files
// diffable: a reviewer must be able to read them side by side and see that they
// are one simulation. That includes keeping the unused `aspect` parameter on
// strokes(), which the fragment version also does not use.
//
// The whole family is this one texture: three "band lanes" of luminous dye
// (r = bass, g = mid, b = treble deposits) advected through a smooth 3D
// velocity field and re-fed every frame. No particles exist anywhere - the
// filaments are the dye's own history, stretched along the flow, which is why
// the picture reads as silk rather than as sprites.
//
// SAFETY / STABILITY: velocities are clamped, the feedback survives only
// through uDecay < 1, and every sample is sanitized - a NaN entering the
// ping-pong would otherwise persist forever.
//
// ---------------------------------------------------------------------------
// THE ONE THING THAT IS NOT A STRAIGHT PORT: there is no texture() here.
//
// State is RGBA32UI with float bits packed into uint channels (§6.3:
// EXT_color_buffer_float is not core in ES 3.0, RGBA32UI is), and RGBA32UI state
// has NO filtering: an integer texture with GL_LINEAR is incomplete, so the only
// fetch available is texelFetch at integer texel coordinates.
//
// The fragment pass's `texture(uPrev, back)` was a hardware bilinear fetch
// (SilkScene allocates its dye DoubleFbo with linear = true), and `back` is a
// back-traced coordinate that lands between texels on essentially every texel of
// every frame. Dropping to a nearest fetch would quantise the advection to whole
// texels: the filaments would stop stretching smoothly and start crawling in
// texel steps, and because the field is fed back into itself that error
// compounds every frame instead of averaging out. It would read as "the compute
// path looks cheaper", which is the worst possible bug report.
//
// So samplePrev() below is a hand-written bilinear fetch with clamp-to-edge.
// This is THE place the two paths would silently diverge, and it is written out
// rather than assumed.
// ---------------------------------------------------------------------------
//
// SUBSTITUTION CONTRACT (plain string replacement by the dispatch layer):
//   %%LOCAL_SIZE_X%%  work-group width  (default 8)
//   %%LOCAL_SIZE_Y%%  work-group height (default 8)
//   %%MATCH_HALF%%    1 or 0, see GEODE_MATCH_HALF below (default 1)
// The tokens are not valid GLSL, so a forgotten substitution fails at
// glCompileShader instead of running at a default nobody chose.

#define GEODE_MATCH_HALF %%MATCH_HALF%%

// The previous state is read through a usampler2D and only the write target is an
// image: image uniforms are the scarce resource at the ES 3.1 floor of four,
// texelFetch goes through the texture cache, and it is the convention SimGlsl
// generates for these families. Same bits as imageLoad.
//
// uPrev must be NEAREST-complete: LINEAR on an integer texture leaves it
// incomplete and every fetch returns zero, silently - which for a feedback field
// means a screen that simply never lights up.
uniform highp usampler2D uPrev;  // texture unit 0
layout(rgba32ui, binding = 0) writeonly uniform highp uimage2D uNext;

uniform vec2 uRes;         // sim texture size, texels
uniform int uField;        // 0..9, see fieldAt()
uniform float uB;          // damping/contraction parameter, breathes slowly
uniform float uAdvect;     // dt * flow, in field units per frame
uniform float uDecay;      // feedback survival per frame, < 1
uniform float uFieldScale; // field domain zoom
uniform float uSwirl;      // how much field z feeds screen rotation
uniform float uSlabX;      // cos/sin pair of the slab orbit phase
uniform float uSlabY;
uniform float uSeedEpoch;  // integer epoch; the stroke lattice re-seats when it steps
uniform float uDrift;      // continuous slow time for the curl field's evolution
uniform float uStrokes;    // injection density multiplier
uniform float uElong;      // filament elongation along the flow
uniform float uDrive;      // audio drive onto injection brightness
uniform float uBass;       // slewed band envelopes, 0..~1.2
uniform float uMid;
uniform float uTreble;
uniform float uBeat;       // graded beat envelope, 0..1.5
uniform float uStrike;     // raw-PCM transient, 0..1.5
uniform float uBeatRing;   // expanding ring radius since the last beat, <0 = none
uniform float uStateScale; // 1 on float targets; the RGBA8 fallback's dye range

const float TAU = 6.2831853;

// -- the state fetch ---------------------------------------------------------

vec4 loadPrevTexel(ivec2 c) {
    ivec2 s = textureSize(uPrev, 0);
    // GL_CLAMP_TO_EDGE, expressed on the texel index because there is no
    // sampler to carry the wrap mode. `back` leaves [0,1] near every border of
    // every frame, so this clamp is load-bearing, not a corner case.
    return uintBitsToFloat(texelFetch(uPrev, clamp(c, ivec2(0), s - ivec2(1)), 0));
}

// MANUAL BILINEAR - the replacement for texture(uPrev, back). See the banner
// above. uv * size - 0.5 is the same half-texel shift a texture unit applies
// before it splits the coordinate into an integer texel and a fractional
// weight; the two mixes are the same two mixes it would perform. What differs
// is precision: a texture unit interpolates with fixed-point weights of
// implementation-defined width (the engine's own filtersLinearly probe accepts
// anything inside [0.30, 0.70] at the exact midpoint for that reason), and this
// interpolates in fp32. The compute result is the more correct one. It is not
// the bit-identical one, and it cannot be made so - the quantity is not
// specified to a bit anywhere in the ES spec.
vec3 samplePrev(vec2 uv) {
    vec2 st = uv * uRes - 0.5;
    ivec2 i = ivec2(floor(st));
    vec2 f = fract(st);
    vec3 a = loadPrevTexel(i).rgb;
    vec3 b = loadPrevTexel(i + ivec2(1, 0)).rgb;
    vec3 c = loadPrevTexel(i + ivec2(0, 1)).rgb;
    vec3 d = loadPrevTexel(i + ivec2(1, 1)).rgb;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// -- field library -----------------------------------------------------------

vec3 fieldAt(int t, vec3 p, float b) {
    if (t == 0) return sin(p.yzx) - b * p;
    if (t == 1) return cos(p.yzx) - b * p;
    if (t == 2) return sin(p.yzx) * sin(p.zxy) - b * p;
    if (t == 3) return sin(p.yzx) * cos(2.2 * length(p)) - b * p;
    if (t == 4) return sin(p.yzx + 0.45 * p.zxy * p.zxy) - b * p;
    if (t == 5) return sin(p.yzx + sin(p.zxy + sin(p.xyz))) - b * p;
    if (t == 6) return tanh(2.2 * p.yzx) - b * p;
    return sin(p.yzx + 0.75 * sin(p.zxy)) - b * p; // 7
}

// Simplex-ish value noise, cheap and periodic enough for a curl field.
vec2 hash2(vec2 q) {
    q = vec2(dot(q, vec2(127.1, 311.7)), dot(q, vec2(269.5, 183.3)));
    return fract(sin(q) * 43758.5453);
}

float vnoise(vec2 q) {
    vec2 i = floor(q);
    vec2 f = fract(q);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash2(i).x;
    float b = hash2(i + vec2(1.0, 0.0)).x;
    float c = hash2(i + vec2(0.0, 1.0)).x;
    float d = hash2(i + vec2(1.0, 1.0)).x;
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y) * 2.0 - 1.0;
}

// Curl of a scalar noise potential: divergence-free swirls.
vec2 curlField(vec2 q, float epoch) {
    float e = 0.15;
    vec2 dq = vec2(
        vnoise(q + vec2(0.0, e) + epoch) - vnoise(q - vec2(0.0, e) + epoch),
        vnoise(q + vec2(e, 0.0) + epoch) - vnoise(q - vec2(e, 0.0) + epoch)
    );
    return vec2(dq.x, -dq.y) / (2.0 * e);
}

// Three attracting poles with a tangential component: pendulum-like orbits.
vec2 poleField(vec2 q, float b) {
    vec2 v = vec2(0.0);
    for (int i = 0; i < 3; i++) {
        float a = TAU * (float(i) / 3.0 + 0.083);
        vec2 pole = 1.05 * vec2(cos(a), sin(a));
        vec2 d = pole - q;
        float r2 = dot(d, d) + 0.09;
        v += d / r2 * 0.6 + vec2(-d.y, d.x) / r2 * 0.45;
    }
    return v - b * 3.0 * q;
}

// -- injection ---------------------------------------------------------------

// Field-aligned luminous strokes at jittered grid seeds. Each seed belongs to
// one band lane (bass wide and central, treble fine and outer), so the music
// literally paints in its own colours.
//
// Note for the other three families porting from this template: THIS is the
// function that will not stay a gather. Silk's strokes are evaluated per texel
// from a hashed lattice - a gather, and it ports unchanged. A family whose
// injection is a scatter (Myco's agents depositing into a field, the fluid's
// splats) is the one that actually collects §6.3's tiler argument: instanced
// deposit quads land at essentially random tiles and force per-primitive,
// per-tile records through Mali's polygon-list build and Adreno's visibility
// stream, and shrinking the quad cuts fragments while doing nothing at all to
// binning cost. A compute dispatch emits no primitives, so it never enters
// binning. That is the strongest argument for this whole tier, and it belongs
// in the deposit kernels - not here, where the honest win is only the removed
// pass overhead.
vec3 strokes(vec2 q, vec2 dir, float aspect) {
    vec3 acc = vec3(0.0);
    float grid = 5.0 * uStrokes;
    vec2 cell = floor(q * grid + uSeedEpoch);
    // 3x3 neighbourhood so strokes cross cell borders without popping.
    for (int oy = -1; oy <= 1; oy++) {
        for (int ox = -1; ox <= 1; ox++) {
            vec2 id = cell + vec2(float(ox), float(oy));
            vec2 h = hash2(id + uSeedEpoch * 0.37);
            vec2 seed = (id + h) / grid;
            vec2 d = q - seed;
            // lane: 0 bass / 1 mid / 2 treble by seed hash
            float lanePick = fract(h.x * 7.31 + h.y * 3.17);
            float along = dot(d, dir);
            float across = dot(d, vec2(-dir.y, dir.x));
            float elong = uElong * (1.0 + 2.0 * lanePick);
            float g = exp(-(along * along) / (0.004 * elong) - (across * across) / 0.00025);
            float w = smoothstep(0.15, 0.9, h.y);
            if (lanePick < 0.34) {
                acc.r += g * w * uBass;
            } else if (lanePick < 0.67) {
                acc.g += g * w * uMid;
            } else {
                acc.b += g * w * (uTreble + 0.5 * uStrike);
            }
        }
    }
    return acc;
}

// Round a packed texel to what the baseline's RGBA16F dye target would have
// stored. See fluid_pressure_comp.glsl for the full argument. It matters more
// here than anywhere else in the engine: this state is a feedback loop, so a
// last-bit difference is not a last-bit difference for long - it is the seed of
// a different picture a few hundred frames later. Both paths being the same
// simulation means the same rounding, not merely the same formula.
vec4 roundToHalf(vec4 v) {
    return vec4(unpackHalf2x16(packHalf2x16(v.xy)), unpackHalf2x16(packHalf2x16(v.zw)));
}

void main() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(uNext);
    // THE BOUNDS GUARD. The dispatch is rounded up to whole work groups, so the
    // last group on each axis carries invocations past the edge of the grid and
    // an unguarded store there writes outside the image. An early return is safe
    // in this kernel only because it contains no barrier() - there is no shared
    // memory here, so no invocation is waiting on any other. (Compare the
    // pressure kernel, where the same guard has to be a guarded store because a
    // barrier() an invocation never reaches is undefined behaviour.)
    if (gid.x >= size.x || gid.y >= size.y) return;

    // The fragment path's vUv, reconstructed. quad_vert's varying interpolates
    // to (i+0.5)/size at a fragment centre; this is that value by a different
    // route, so they can differ in the last bit and there is no way to ask a
    // compute invocation for the rasterizer's answer.
    vec2 uv = (vec2(gid) + 0.5) / uRes;
    float aspect = uRes.x / uRes.y;
    vec2 q = (uv - 0.5) * vec2(aspect, 1.0) * (3.2 * uFieldScale);

    vec2 v;
    if (uField == 8) {
        v = curlField(q * 1.4, uDrift) * 0.9;
    } else if (uField == 9) {
        v = poleField(q, uB);
    } else {
        // Embed the screen slice in the 3D field: the slab orbits slowly so
        // the projection keeps discovering new structure without resets.
        vec3 p3 = vec3(q, 1.1 * uSlabY);
        p3.xz = mat2(uSlabX, -uSlabY, uSlabY, uSlabX) * p3.xz;
        vec3 v3 = fieldAt(uField, p3, uB);
        float r = max(length(q), 0.3);
        v = v3.xy + uSwirl * v3.z * vec2(-q.y, q.x) / r;
    }
    v = clamp(v, vec2(-4.0), vec2(4.0));

    // Beat impulse: a radial push away from centre, decaying with uBeat.
    v += normalize(q + vec2(1e-4)) * uBeat * 0.35;

    vec2 back = uv - v * uAdvect / vec2(aspect, 1.0);
    // uStateScale unpacks the RGBA8 fallback's pre-scaled dye; 1 on float. On
    // this path it is always 1 - RGBA32UI holds the dye at full range and the
    // RGBA8 fallback belongs to the ES 3.0 baseline - but the uniform stays so
    // the two files diff clean and so nothing here depends on that being true.
    vec3 prev = samplePrev(back) * uStateScale;
    // Sanitize the loop: a NaN or runaway would persist forever.
    prev = clamp(prev, vec3(0.0), vec3(8.0));
    prev = mix(prev, vec3(dot(prev, vec3(0.3333))), 0.012); // slow desaturate
    prev *= uDecay;

    vec2 dir = normalize(v + vec2(1e-4));
    vec3 add = strokes(q, dir, aspect) * (0.55 * uDrive);

    // The expanding beat ring deposits into the bass lane.
    if (uBeatRing >= 0.0) {
        float ring = exp(-pow((length(q) - uBeatRing) * 9.0, 2.0));
        add.r += ring * uBeat * 0.8;
    }

    vec3 color = max(prev, add) + add * 0.3;
    vec4 result = vec4(min(color, vec3(8.0)) / uStateScale, 1.0);
#if GEODE_MATCH_HALF
    result = roundToHalf(result);
#endif
    imageStore(uNext, gid, floatBitsToUint(result));
}

// WHERE THIS CAN STILL DIVERGE FROM THE FRAGMENT PASS
//
// 1. samplePrev() vs a texture unit's fixed-point filter weights. Unfixable by
//    construction, and it compounds because this field feeds itself: the two
//    paths stay statistically identical and stop being pixel-identical within a
//    few seconds of motion. If a parity test is ever written for Silk it must
//    compare distributions, not frames.
// 2. uv reconstruction, as noted above.
// 3. sin/cos/exp/tanh/pow/normalize are all implementation-defined to a few ulp
//    in ESSL, and there is no guarantee a driver's fragment and compute stages
//    share an implementation of them. This kernel is dense in them - fieldAt(),
//    vnoise() and strokes() together evaluate dozens per texel - so even with
//    identical inputs and identical rounding the two paths can part ways. This
//    is the argument against ever making the compute tier the reference: the
//    baseline is the reference because it is the one that ships everywhere.
