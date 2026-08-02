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
/** Family substyle: 0 original, 1..10 authored variants. */
uniform int uStyle;

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

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

/**
 * The shared resonator remains the source of every variant. This transform is
 * the family-composition layer: it bends the same modal field into membranes,
 * shells, chambers and tubes without forking the audio or phase logic.
 */
vec2 styleCoordinates(vec2 p, vec2 uv) {
    if (uStyle == 2) { // Drumhead: a breathing, tensioned membrane.
        p *= 0.94 + 0.055 * sin(uTime * 0.7);
    } else if (uStyle == 3) { // Harmonograph: two slowly precessing pendulums.
        p += 0.24 * vec2(sin(p.y * 0.72 + uTime * 0.31), cos(p.x * 0.66 - uTime * 0.27));
    } else if (uStyle == 4) { // Faraday: subharmonic surface buckling.
        p += 0.11 * vec2(sin(p.y * 2.1 - uTime * 0.8), sin(p.x * 1.8 + uTime * 0.72));
    } else if (uStyle == 5) { // Shell: unwrap polar coordinates into a living spiral.
        float r = length(p);
        float a = atan(p.y, p.x);
        p = vec2(a * 0.92 + r * 0.62, r * 2.0 - 1.9 + 0.12 * sin(a * 5.0 + uTime * 0.2));
    } else if (uStyle == 6) { // Caustic sheet: refraction through a second wave layer.
        p += 0.16 * sin(p.yx * 1.65 + vec2(0.0, 1.7) + uTime * 0.26);
    } else if (uStyle == 7) { // Levitator: mirrored pressure nodes above a central plane.
        p.y = sign(p.y) * (abs(p.y) * 0.72 + 0.42);
        p.x += 0.12 * sin(p.y * 2.8 + uTime * 0.25);
    } else if (uStyle == 8) { // Chamber: perspective compression toward a deep back wall.
        float z = 1.0 / max(0.62, 1.25 + uv.y * 0.52);
        p = vec2(p.x * z, p.y * 0.72 + 0.22 / z);
    } else if (uStyle == 9) { // Rosensweig: magnetic cells pull toward a hex-like lattice.
        p += 0.07 * vec2(sin(p.y * 3.4), sin(p.x * 3.4 + 2.094));
    } else if (uStyle == 10) { // Kundt tube: long axis with a curved glass wall.
        p = vec2(p.x * 0.58, sin(p.y * 0.58) * 1.65);
    }
    return p;
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
    p = styleCoordinates(p, uv);

    // Normalized displacement: -1..1 whatever is playing, so every threshold
    // below means the same thing at any loudness. A few variants combine a
    // second sample of THE SAME resonator: this is a compositional merge, not
    // another audio engine running out of phase with it.
    float h = field(p) * uHeightNorm;
    if (uStyle == 3) {
        float a2 = 0.74 + 0.18 * sin(uTime * 0.09);
        mat2 r2 = mat2(cos(a2), -sin(a2), sin(a2), cos(a2));
        h = h * 0.58 + field(r2 * p * 0.82 + vec2(0.35, -0.18)) * uHeightNorm * 0.42;
    } else if (uStyle == 4) {
        float sub = field(p * 1.62 + vec2(0.16, -0.11)) * uHeightNorm;
        h = sin(h * 2.45 + sub * 1.15) * 0.72;
    } else if (uStyle == 5) {
        h = h * 0.72 + field(p.yx * vec2(-0.68, 0.68)) * uHeightNorm * 0.28;
    } else if (uStyle == 9) {
        h = sign(h) * pow(max(abs(h), 1e-4), 0.48);
    } else if (uStyle == 10) {
        h = h * 0.72 + field(vec2(p.x * 1.8, p.y * 0.42)) * uHeightNorm * 0.28;
    }
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

    // Material signatures. They deliberately reuse h, its derivatives and the
    // family palette, so changing a Cymatics control still means the same
    // thing in every substyle.
    if (uStyle == 1) { // Chladni Sand
        float grains = smoothstep(0.42, 0.94, hash21(floor(gl_FragCoord.xy * 0.72) + floor(p * 7.0)));
        color *= 0.46;
        color += mix(body, vec3(1.0), 0.58) * nodal * (0.34 + 1.15 * grains);
    } else if (uStyle == 2) { // Drumhead
        float rim = 1.0 - smoothstep(0.0, 0.16, abs(length(uv) - (0.78 + 0.025 * sin(uTime * 0.7))));
        color += ridge * rim * (0.35 + 0.4 * clamp(uEnergy, 0.0, 1.5));
        color *= 1.0 - smoothstep(0.78, 1.12, length(uv));
    } else if (uStyle == 3) { // Harmonograph
        float etched = exp(-abs(sin(h * 15.0 + p.x * 1.2 - p.y * 0.7)) * 8.0);
        color += body * etched * (0.18 + 0.38 * uGlow);
    } else if (uStyle == 4) { // Faraday
        float cells = pow(clamp(1.0 - abs(h), 0.0, 1.0), 5.0);
        color += body * cells * (0.18 + 0.45 * uCaustic) * (0.5 + 0.5 * sin(uTime * 0.5 + h * 8.0));
    } else if (uStyle == 5) { // Harmonic Shell
        float shellRim = pow(clamp(1.0 - dot(uv * 0.72, uv * 0.72), 0.0, 1.0), 0.36);
        float pearl = pow(clamp(dot(nrm, normalize(vec3(-0.25, 0.45, 0.86))), 0.0, 1.0), 9.0);
        color = color * (0.45 + 0.75 * shellRim) + ridge * pearl * 0.55;
    } else if (uStyle == 6) { // Caustic Sheet
        float focus = pow(clamp(1.0 - length(g) / (w * 1.7), 0.0, 1.0), 7.0);
        color += mix(body, vec3(1.0), 0.72) * focus * (0.25 + 0.75 * uCaustic);
    } else if (uStyle == 7) { // Levitator
        float beads = smoothstep(0.72, 0.96, sin(p.x * 8.0 + h * 4.0) * sin(p.y * 7.0 - uTime * 0.3) * 0.5 + 0.5);
        float antinode = smoothstep(0.42, 0.82, az);
        color += ridge * beads * antinode * (0.35 + 0.35 * clamp(uTreble, 0.0, 1.5));
    } else if (uStyle == 8) { // Standing Chamber
        float rails = exp(-abs(fract((p.x + h * 0.18) * 0.45) - 0.5) * 18.0);
        float depthFade = 1.0 - smoothstep(-0.85, 1.35, uv.y);
        color = color * (0.42 + 0.58 * depthFade) + body * rails * halo * 0.42;
    } else if (uStyle == 9) { // Rosensweig Spikes
        float peaks = pow(smoothstep(0.18, 0.92, az), 3.5);
        color += mix(body, vec3(1.0), 0.5) * peaks * (0.34 + 0.45 * diffuse + 0.25 * uBeat);
        color *= 0.78 + 0.36 * peaks;
    } else if (uStyle == 10) { // Kundt Tube
        float wall = 1.0 - smoothstep(0.0, 0.11, abs(abs(uv.y) - 0.76));
        float dust = smoothstep(0.54, 0.9, hash21(floor(gl_FragCoord.xy * vec2(0.42, 0.8)))) * nodal;
        color *= 1.0 - smoothstep(0.68, 0.92, abs(uv.y));
        color += ridge * wall * 0.42 + body * dust * 0.45;
    }

    // Filmic-ish roll-off: the sum above is HDR by construction (three
    // additive layers), and clipping it would flatten every bright ridge into
    // the same white blob.
    color = vec3(1.0) - exp(-color * uExposure);
    fragColor = vec4(color, 1.0);
}
