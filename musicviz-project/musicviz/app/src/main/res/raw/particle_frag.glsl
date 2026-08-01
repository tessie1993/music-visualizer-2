#version 300 es
// Emissive particle shading: an antialiased SDF body, an inverse-square glow
// aura, and a hot core, mixed in linear light and tone-mapped down to the
// 8-bit scene buffer.
//
// The old shader built each shape from a bare exp() falloff and painted it a
// flat hsv2rgb colour, which is what made the particle styles read as soft,
// hazy blobs next to the fluid/shader families. What replaced it:
//   * shapes are signed distance fields (Inigo Quilez's 2D SDF primitives,
//     iquilezles.org/articles/distfunctions2d) antialiased with fwidth, so
//     silhouettes are crisp at any sprite size instead of shimmering;
//   * an inverse-square aura around the sprite - a point-spread function, not
//     a cutoff - so overlapping particles sum into a real glow;
//   * brightness is carried by the interior falloff, so a sprite is a luminous
//     point with a defined edge rather than a flat disc;
//   * hot cores desaturate toward white and the aura disperses slightly in
//     hue, the way actual emissive sources photograph;
//   * ACES filmic tone mapping (Narkowicz's fit) so bright cores roll off to
//     white instead of clipping flat, plus a 1-LSB dither that kills the
//     banding an 8-bit target puts across wide soft gradients.
precision highp float;

in vec2 vShape;
in float vHue;
in float vEnergy;
in float vSeed;
in float vFade;
in float vStretch;
out vec4 fragColor;

uniform float uSat;
uniform float uBright;
uniform float uContrast;
uniform float uGamma;
uniform float uShape;
uniform float uGlow;   // aura weight; rides the Bloom slider
uniform float uTime;

// Shape radius in quad space (the quad edge is 1.0); the remainder is the
// margin the aura falls off across. SHAPE_R * particle_vert's GLOW_EXTENT is
// ~1, which is what keeps the body on the sprite radius the size attribute
// asks for - raise one and the shapes grow, raise the other and they shrink.
const float SHAPE_R = 0.30;

vec3 hsv2rgb(vec3 c) {
    vec4 k = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + k.xyz) * 6.0 - k.www);
    return c.z * mix(k.xxx, clamp(p - k.xxx, 0.0, 1.0), c.y);
}

// --- 2D SDF primitives (Inigo Quilez, iquilezles.org/articles/distfunctions2d)
float sdCircle(vec2 p, float r) {
    return length(p) - r;
}

float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float sdHexagon(vec2 p, float r) {
    const vec3 k = vec3(-0.866025404, 0.5, 0.577350269);
    p = abs(p);
    p -= 2.0 * min(dot(k.xy, p), 0.0) * k.xy;
    p -= vec2(clamp(p.x, -k.z * r, k.z * r), r);
    return length(p) * sign(p.y);
}

float sdStar(vec2 p, float r, float n, float m) {
    float an = 3.141593 / n;
    float en = 3.141593 / m;
    vec2 acs = vec2(cos(an), sin(an));
    vec2 ecs = vec2(cos(en), sin(en));
    float bn = mod(atan(p.x, p.y), 2.0 * an) - an;
    p = length(p) * vec2(cos(bn), abs(sin(bn)));
    p -= r * acs;
    p += ecs * clamp(-dot(p, ecs), 0.0, r * acs.y / ecs.y);
    return length(p) * sign(p.x);
}

float sdCross(vec2 p, vec2 b) {
    p = abs(p);
    p = (p.y > p.x) ? p.yx : p.xy;
    vec2 q = p - b;
    float k = max(q.y, q.x);
    vec2 w = (k > 0.0) ? q : vec2(b.y - p.x, -k);
    return sign(k) * length(max(w, 0.0));
}

/** Signed distance to the selected shape's surface (index = uShape). */
float shapeField(vec2 p, float shape) {
    if (shape < 0.5) {
        return sdCircle(p, SHAPE_R);
    } else if (shape < 1.5) {
        // Ring: an annulus around the circle instead of an exp() band.
        return abs(sdCircle(p, SHAPE_R * 0.84)) - SHAPE_R * 0.17;
    } else if (shape < 2.5) {
        return sdStar(p, SHAPE_R * 1.06, 5.0, 2.6);
    } else if (shape < 3.5) {
        return sdRoundedBox(p, vec2(SHAPE_R * 0.80), SHAPE_R * 0.26);
    } else if (shape < 4.5) {
        // Spark: a rounded cross with a bead at the crossing.
        float arms = sdCross(p, vec2(SHAPE_R * 1.15, SHAPE_R * 0.11)) - SHAPE_R * 0.05;
        return min(arms, sdCircle(p, SHAPE_R * 0.20));
    } else if (shape < 5.5) {
        return sdHexagon(p, SHAPE_R * 0.90);
    } else {
        // Bubble: a thin shell (the faint fill is added as a separate weight).
        return abs(sdCircle(p, SHAPE_R * 0.92)) - SHAPE_R * 0.07;
    }
}

/** Four-armed diffraction streak, the lens-flare cue that reads as "bright". */
float spikes(vec2 p) {
    vec2 a = abs(p);
    return exp(-a.y * 46.0 - a.x * 3.4) + exp(-a.x * 46.0 - a.y * 3.4);
}

/** ACES filmic curve, Krzysztof Narkowicz's fit of the RRT+ODT. */
vec3 acesFilmic(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

/** Dave Hoskins' sine-free hash (shadertoy.com/view/4djSRW) - the sin()-based
 *  one banded visibly on mobile GPUs at reduced precision. */
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    if (vFade <= 0.0) discard;

    vec2 p = vShape;
    float d = shapeField(p, uShape);
    // Analytic AA: one screen pixel measured in shape space. Constant-width
    // edges at every sprite size, which a fixed smoothstep cannot give.
    float aa = max(fwidth(d), 1e-4);
    float body = 1.0 - smoothstep(-aa, aa, d);
    // Inverse-square aura: the point-spread function of a small bright source,
    // centred on the particle rather than pushed out from its surface. Keyed
    // on radius, not the SDF, so it cannot plateau across a filled shape - a
    // flat interior aura is what turns a few thousand stacked sprites into a
    // white wash. It never quite reaches zero inside the quad either, so
    // overlapping particles sum into a glow instead of a pile of discs.
    float r = length(p);
    float falloff = 1.0 / (1.0 + 16.0 * r * r);
    float aura = falloff * sqrt(falloff);
    // Interior falloff: deepest inside the shape is hottest. Thin shells
    // (Ring, Bubble) have almost no interior and so stay rim-lit, which is
    // exactly right for them.
    float core = pow(clamp(-d / (SHAPE_R * 0.9), 0.0, 1.0), 1.5);

    float e = clamp(vEnergy, 0.0, 1.0);
    // Star and Spark get diffraction arms; every other shape stays clean.
    float spikeAmt = ((uShape > 1.5 && uShape < 2.5) || (uShape > 3.5 && uShape < 4.5)) ? 0.55 : 0.0;
    if (spikeAmt > 0.0) aura += spikes(p) * spikeAmt * (0.35 + 0.65 * e);
    // Bubble is a shell plus a faint fill and an off-centre highlight - the
    // specular pin is what separates it from Ring at a glance.
    if (uShape > 5.5) {
        body += smoothstep(SHAPE_R, 0.0, r) * 0.12;
        body += smoothstep(SHAPE_R * 0.40, 0.0, length(p - vec2(-0.40, 0.40) * SHAPE_R)) * 0.5;
    }

    float glow = aura * uGlow;
    float weight = body + glow + core;
    if (weight < 1e-4) discard;

    // Colour: the palette hue owns the body, the aura disperses a touch
    // outward in hue, and the core whitens with energy.
    float sat = clamp(uSat * (0.98 - 0.30 * e), 0.0, 1.0);
    vec3 bodyCol = hsv2rgb(vec3(vHue, sat, 1.0));
    vec3 auraCol = hsv2rgb(vec3(fract(vHue + 0.045), min(sat * 1.15, 1.0), 1.0));
    vec3 coreCol = mix(bodyCol, vec3(1.0), 0.10 + 0.45 * e);
    // Graded unpremultiplied, then weighted - grading the premultiplied value
    // would push the contrast pedestal into fully transparent pixels and box
    // every sprite in a faint square.
    vec3 tint = (bodyCol * body + auraCol * glow + coreCol * core) / weight;
    tint = (tint - 0.5) * uContrast + 0.5;
    tint = pow(max(tint, 0.0), vec3(1.0 / max(uGamma, 0.05)));

    // Emission. Stretching spreads one particle's light over a longer quad, so
    // divide it back out or fast particles would read as brighter ones.
    float twinkle = 0.88 + 0.12 * sin(uTime * 5.3 + vSeed * 6.2831853);
    float amp = (0.32 + 0.68 * e) * vFade * twinkle / sqrt(vStretch);
    vec3 hdr = tint * (body * 0.40 + glow * 0.90 + core * 1.50) * amp * uBright;
    vec3 color = acesFilmic(hdr);
    // 1-LSB dither: wide soft gradients band badly on an 8-bit target.
    color += (hash12(gl_FragCoord.xy + vSeed) - 0.5) / 255.0;

    // Premultiplied output. Coverage counts the body and core only: the aura
    // must not occlude what is behind it, so it lands as pure added light.
    float alpha = clamp(body * 0.85 + core * 0.55, 0.0, 1.0) * (0.32 + 0.68 * e) * vFade;
    // Invert is owned by the composite pass (uPostInvert) so it is applied
    // exactly once to the whole frame.
    fragColor = vec4(max(color, 0.0), alpha);
}
