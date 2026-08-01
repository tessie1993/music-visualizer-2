#version 300 es
precision highp float;

// The CYMATICS style: the standing-wave field of the sound, evaluated PER
// PIXEL and filling the whole screen. No plate, no camera, no black surround
// - the wave field IS the picture, edge to edge, and it flows because its
// modes keep moving.
//
// Two geometries, both driven by the same ringing modes:
//   uGeometry 0  a water dish: circular-membrane modes, J_m(beta*r)cos(m*a)
//                - concentric rings crossed by petals, the CymaScope look;
//   uGeometry 1  a Chladni plate: the square-plate formula, a nodal lattice.
//
// The nodal rendering - a narrow Gaussian on |h| for the bright filigree plus
// a wide one for its halo - follows the approach taken by Naadara, the MIT
// licensed open cymatics laboratory (see THIRD_PARTY_NOTICES). The modal
// field, the travelling-wave flow, the iridescent dispersion and the caustic
// shading are this app's own.

in vec2 vUv;
out vec4 fragColor;

uniform vec2 uResolution;
uniform float uTime;

/** (order a, order b, amplitude, phase) per ringing mode. */
uniform vec4 uModes[8];
uniform int uModeCount;

uniform float uGeometry;
uniform float uScale;
/** 1 / peak displacement, so the shading works in normalized height. */
uniform float uHeightNorm;
uniform float uLine;
uniform float uGlow;
uniform float uIridescence;
uniform float uCaustic;
/** 0 = bare filigree on dark cells .. 1 = the whole surface filled in. */
uniform float uFill;
uniform float uSwirl;
uniform float uTravel;
uniform float uBaseHue;
uniform float uHueSpan;
uniform float uEnergy;
uniform float uTreble;
uniform float uBeat;
uniform float uExposure;

const float PI = 3.14159265359;

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

/**
 * Bessel J_m, cheap: the asymptotic form (amplitude ~ sqrt(2/pi x), zeros a
 * quarter period apart) with a core factor so an angular order m > 0 vanishes
 * at the centre of the dish as the real function does. Mirrored by
 * CymaticsMath.besselApprox, which is pinned against a series expansion.
 */
float besselApprox(float m, float x, float phase) {
    // J_0 peaks AT the centre, every higher order vanishes there: without the
    // step the core factor punched a black hole through the middle of the
    // dish for every mode alike.
    float ax = abs(x);
    float core = mix(1.0, ax * ax / (ax * ax + 0.45 * m * m + 0.05), step(0.5, m));
    float w = x - m * PI * 0.5 - PI * 0.25 - phase;
    // Two terms of Hankel's expansion, not one: the leading cosine alone puts
    // the INNERMOST rings badly out (J_4's first zero at 7.85 instead of
    // 7.59), and those are the rings filling the middle of the screen. With
    // both terms every ring lands within ~0.05 of the real J_m's zero.
    // Clamped only to bound the terms near the centre, where they diverge and
    // the core factor above owns the shape anyway.
    float inv = 1.0 / (8.0 * max(ax, 0.75));
    float mu = 4.0 * m * m;
    float c1 = clamp((mu - 1.0) * inv, -3.0, 3.0);
    float c0 = clamp(1.0 - (mu - 1.0) * (mu - 9.0) * inv * inv * 0.5, -3.0, 3.0);
    return (c0 * cos(w) - c1 * sin(w)) * inversesqrt(1.0 + 2.0 * ax) * core * 1.7;
}

/** The dish/plate displacement at [p], as the sum of every ringing mode. */
float field(vec2 p) {
    float h = 0.0;
    if (uGeometry < 0.5) {
        float r = length(p);
        float a = atan(p.y, p.x);
        for (int i = 0; i < 8; i++) {
            if (i >= uModeCount) break;
            vec4 M = uModes[i];
            // Angular order and radial order, out of the same (n, m) pair the
            // square plate reads as its own two orders.
            float ang = M.y;
            float rad = max(M.x - M.y, 1.0);
            // McMahon: the s-th zero of J_m sits near pi(s + m/2 - 1/4).
            float beta = PI * (rad + 0.5 * ang - 0.25);
            // "Flow" turns the standing wave into a travelling one: the rings
            // march outward the way a driven dish sheds them, faster for the
            // finer modes. Applied as a PHASE, not as a shift of the radius -
            // shifting the radius pushed the argument negative near the centre
            // and the amplitude term returned NaN, which showed up as a black
            // hole punched through the middle of the dish.
            float travel = uTravel * uTime * (0.7 + 0.09 * beta);
            h += M.z * besselApprox(ang, beta * r, travel) * cos(ang * a + M.w);
        }
    } else {
        for (int i = 0; i < 8; i++) {
            if (i >= uModeCount) break;
            vec4 M = uModes[i];
            float n = M.x;
            float m = M.y;
            // The plate's own flow is a slow drift of the lattice, so the
            // figure breathes instead of standing frozen.
            vec2 q = p + vec2(0.0, uTravel * uTime * 0.05);
            float z = cos(n * PI * q.x) * cos(m * PI * q.y) - cos(m * PI * q.x) * cos(n * PI * q.y);
            h += M.z * z * cos(M.w);
        }
    }
    return h;
}

void main() {
    // Screen -> field coordinates. The field CONTINUES past the edges of the
    // screen: there is no rim to frame, so nothing is ever letterboxed.
    vec2 uv = vUv * 2.0 - 1.0;
    uv.x *= uResolution.x / max(uResolution.y, 1.0);
    float s = sin(uSwirl * uTime);
    float c = cos(uSwirl * uTime);
    vec2 p = mat2(c, -s, s, c) * uv * uScale;

    // Normalized displacement: -1..1 whatever is playing, so every threshold
    // below means the same thing at any loudness.
    float h = field(p) * uHeightNorm;
    float az = abs(h);

    vec2 g = vec2(dFdx(h), dFdy(h));
    float w = max(fwidth(h), 1e-5);

    // Nodal filigree and its halo: the sand of a plate, the standing ridges
    // of a dish. Both widths are measured in local slope, so a line keeps the
    // same weight on screen whether the figure is coarse or dense.
    float narrow = w * (0.6 + 1.3 * uLine);
    float wide = narrow * 4.0;
    float nodal = exp(-(az * az) / (narrow * narrow));
    float halo = exp(-(az * az) / (wide * wide));

    // Caustic sheen: light through a wavy surface piles up where the surface
    // is flat, which is what gives water cymatics its glassy plateaus.
    // ("flat" is a reserved interpolation qualifier in GLSL ES 3.00.)
    float plateau = clamp(1.0 - length(g) / w, 0.0, 1.0);
    float caustic = pow(plateau, 4.0) * uCaustic * smoothstep(0.15, 0.6, az);

    // Iridescence: the palette is sampled at three slightly different heights
    // for R/G/B, so slopes fringe into rainbow exactly as a thin film does.
    float disp = uIridescence * (0.02 + 0.05 * clamp(length(g) / w, 0.0, 1.0));
    // Hue tracks displacement WITHOUT being squeezed into one turn of the
    // wheel: a wide palette span therefore bands the field into repeating
    // colour rings (hue is cyclic, so the wrap is seamless) instead of
    // painting the whole figure one tint with a slight gradient.
    float hue = uBaseHue + uHueSpan * h;
    vec3 body =
        vec3(
            hsv2rgb(vec3(fract(hue - disp), 0.9, 1.0)).r,
            hsv2rgb(vec3(fract(hue), 0.9, 1.0)).g,
            hsv2rgb(vec3(fract(hue + disp), 0.9, 1.0)).b
        );

    // Relief: a surface normal from the slope DIRECTION only (the magnitude
    // is divided out), so the field embosses the same whether the figure is
    // three rings wide or thirty, and a light can play across it.
    vec3 nrm = normalize(vec3(-g / w, 0.9));
    vec3 lightDir = normalize(vec3(0.35, 0.55, 0.75));
    float diffuse = clamp(dot(nrm, lightDir), 0.0, 1.0);
    float spec = pow(clamp(dot(reflect(-lightDir, nrm), vec3(0.0, 0.0, 1.0)), 0.0, 1.0), 22.0);

    // The surface itself. "Fill" runs from bare filigree over dark cells (the
    // sand-on-a-plate reading) to a fully filled iridescent surface (the
    // liquid reading); level keeps both honest to how loud the track is.
    float level = 0.35 + 0.75 * clamp(uEnergy, 0.0, 1.5);
    vec3 color = body * (0.04 + 0.20 * az * az + uFill * (0.10 + 0.80 * diffuse)) * level;

    // Halo (broad, palette-coloured), then the filigree on top (near white,
    // treble glinting on it and beats flaring it), then the caustic sheen.
    color += body * halo * uGlow * 0.45 * level;
    vec3 ridge = mix(vec3(1.0), body, 0.4);
    color += ridge * nodal * (0.7 + 0.45 * clamp(uTreble, 0.0, 1.5) + 0.35 * clamp(uBeat, 0.0, 1.0));
    color += ridge * (caustic + spec * uCaustic * 0.8) * (0.2 + 0.3 * clamp(uEnergy, 0.0, 1.5));

    // Filmic-ish roll-off: the sum above is HDR by construction (three
    // additive layers), and clipping it would flatten every bright ridge into
    // the same white blob.
    color = vec3(1.0) - exp(-color * uExposure);
    fragColor = vec4(color, 1.0);
}
