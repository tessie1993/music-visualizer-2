#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform float uTime;
uniform vec2 uResolution;
uniform float uBass;
uniform float uMid;
uniform float uTreble;
uniform float uEnergy;
uniform float uBeat;
uniform sampler2D uAudioTex;
uniform float uSpeed;
uniform float uZoom;
uniform float uRotation;
uniform float uZoomPhase;
uniform float uColorShift;
uniform float uHueRange;
uniform float uSat;
uniform float uBright;
uniform float uInvert;
uniform float uIntensity;
uniform float uMirrorX;
uniform float uBeatResponse;
uniform float uTurbulence;
uniform float uPalBase;
uniform float uPalRange;
uniform float uContrast;
uniform float uGamma;
uniform float uPal2Base;
uniform float uPal2Range;
uniform float uPaletteMix;
uniform float uDuotone;
uniform float uBloom;
uniform float uWarp;
uniform float uRipple;
uniform float uSymmetry;
uniform float uKaleido;
uniform float uMorph;
uniform float uPixelate;
uniform float uPosterize;
uniform float uSway;
uniform float uPulse;
uniform float uBeatPhase;
uniform float uDriftX;
uniform float uDriftY;
uniform float uShake;
uniform float uTile;
uniform float uTwist;
uniform float uTemperature;
uniform float uSolarize;
uniform float uFlash;
// FlowField / finger smear: shared fluid velocity field in screen space.
// Half-float velocities exceed lowp range.
uniform highp sampler2D uFlow;
uniform float uFlowStrength;

float aband(float x) { return texture(uAudioTex, vec2(clamp(x, 0.0, 1.0), 0.25)).r; }
float awave(float x) { return texture(uAudioTex, vec2(clamp(x, 0.0, 1.0), 0.75)).r; }

vec2 view() {
    vec2 uv = vUv * 2.0 - 1.0;
    uv.x *= uResolution.x / uResolution.y;
    if (uPixelate > 0.001) {
        float px = mix(1.0, 12.0, uPixelate) * 24.0;
        uv = floor(uv * px) / px;
    }
    if (uMirrorX > 0.5) uv.x = abs(uv.x);
    // Kaleidoscope: fold the plane into uSymmetry angular wedges.
    if (uKaleido > 0.5 && uSymmetry >= 2.0) {
        float ang = atan(uv.y, uv.x);
        float rad = length(uv);
        float seg = 6.2831853 / uSymmetry;
        ang = abs(mod(ang, seg) - seg * 0.5);
        uv = vec2(cos(ang), sin(ang)) * rad;
    }
    uv += vec2(uDriftX, uDriftY) * uTime * 0.1;
    uv += uShake * uBeat * 0.03 * vec2(sin(uTime * 91.7), cos(uTime * 77.3));
    // Morph: blend the plane toward a polar remap (angle,radius swap), a
    // smooth geometric metamorphosis that works on any scene.
    if (uMorph > 0.001) {
        float mr = length(uv);
        float ma = atan(uv.y, uv.x);
        vec2 polar = vec2(ma / 3.14159, (mr - 0.7) * 1.6);
        uv = mix(uv, polar, uMorph * (0.6 + 0.15 * sin(uTime * 0.31)));
    }
    float a = uRotation + uSway * 0.35 * sin(uTime * 0.7);
    uv = mat2(cos(a), -sin(a), sin(a), cos(a)) * uv;
    // Beat-locked pulse: peaks exactly on the musical beat (uBeatPhase=0).
    float pulse = 1.0 + uPulse * 0.22 * pow(0.5 + 0.5 * cos(6.2831853 * uBeatPhase), 2.0);
    float z = uZoom * pulse * pow(2.0, uZoomPhase) * (1.0 + uBeat * uBeatResponse * 0.15);
    uv /= max(z, 0.05);
    uv += uTurbulence * 0.06 * vec2(sin(uv.y * 6.0 + uTime), cos(uv.x * 6.0 + uTime * 1.3));
    // Radial twist: rotate by an angle growing with radius.
    if (abs(uTwist) > 0.001) {
        float tr = length(uv) * uTwist * 2.0;
        uv = mat2(cos(tr), -sin(tr), sin(tr), cos(tr)) * uv;
    }
    // Tiling: repeat the plane into a uTile x uTile grid.
    if (uTile > 1.01) {
        uv = mod(uv * uTile * 0.5 + 1.0, 2.0) - 1.0;
    }
    // Domain warp: swirl coordinates by a sin/cos field.
    if (uWarp > 0.001) {
        float w = uWarp * 0.5;
        uv += w * vec2(sin(uv.y * 3.0 + uTime * 1.1), cos(uv.x * 3.0 + uTime * 0.9));
    }
    // Concentric ripple distortion driven by radius.
    if (uRipple > 0.001) {
        float r = length(uv);
        uv *= 1.0 + uRipple * 0.15 * sin(r * 14.0 - uTime * 3.0 + uBass * 4.0);
    }
    return uv;
}

vec3 pal(float t) {
    vec3 a = 0.5 + 0.5 * cos(6.2831 * (uPalBase + uColorShift + t * uPalRange * uHueRange + vec3(0.0, 0.33, 0.67)));
    vec3 b = 0.5 + 0.5 * cos(6.2831 * (uPal2Base + uColorShift + t * uPal2Range * uHueRange + vec3(0.0, 0.33, 0.67)));
    return mix(a, b, uPaletteMix);
}

vec3 grade(vec3 col) {
    if (uBloom > 0.001) col += uBloom * col * col;
    if (uPosterize > 0.001) {
        float levels = mix(24.0, 3.0, uPosterize);
        col = floor(col * levels + 0.5) / levels;
    }
    float g = dot(col, vec3(0.299, 0.587, 0.114));
    if (uDuotone > 0.5) col = pal(g);
    col = mix(vec3(g), col, uSat);
    col = (col - 0.5) * uContrast + 0.5;
    col = pow(max(col, 0.0), vec3(1.0 / max(uGamma, 0.05)));
    col.r += uTemperature * 0.12;
    col.b -= uTemperature * 0.12;
    if (uSolarize > 0.5) col = abs(1.0 - 2.0 * col);
    col += uFlash * uBeat * 0.6;
    col = col * uBright * uIntensity;
    return mix(col, max(vec3(1.0) - col, 0.0), uInvert);
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

// Smear field: the shared fluid velocity (FlowField service or finger
// splats), soft-limited exactly like the composite fluidWarp so a hard drag
// bends instead of tearing. Sampled in screen space, returned in scene units.
vec2 smear(vec2 suv) {
    vec2 f = texture(uFlow, clamp(suv, 0.0, 1.0)).xy;
    f *= 6.0 / (6.0 + length(f));
    return f * uFlowStrength;
}

// WINTER: frozen-pond interference ripples + drifting parallax snowfall +
// voronoi frost crystals, all advected by the smear field - drag a finger
// (or enable FlowField) and water, frost and snow swirl together.
void main() {
    vec2 p = view();
    // Finger smear displaces the whole scene domain; deeper layers less.
    vec2 fl = smear(vUv);
    p -= fl * 0.22;
    float t = uTime;

    // ---- icy water: interference ripples from three drifting sources ----
    vec2 c1 = vec2(sin(t * 0.30), cos(t * 0.23)) * 0.55;
    vec2 c2 = vec2(cos(t * 0.19 + 2.1), sin(t * 0.27 + 1.3)) * 0.65;
    float r0 = length(p);
    float r1 = length(p - c1);
    float r2 = length(p - c2);
    float wave = sin(r0 * (18.0 + uBass * 10.0) - t * 3.2) * exp(-r0 * 1.2);
    wave += sin(r1 * 24.0 - t * 4.1) * exp(-r1 * 1.8) * (0.5 + uMid * 1.6);
    wave += sin(r2 * 30.0 - t * 5.0) * exp(-r2 * 2.2) * (0.3 + uTreble * 1.4);
    // Beat rings skate outward across the ice.
    float ring = exp(-abs(r0 - fract(uBeatPhase) * 1.7) * 12.0) * uBeat * uBeatResponse;
    // Cool water body, brightened along wave crests (specular shimmer).
    float crest = pow(clamp(wave * 0.5 + 0.5, 0.0, 1.0), 3.0);
    vec3 col = pal(0.15 + wave * 0.25) * (0.16 + 0.30 * crest + uEnergy * 0.35);
    col += pal(0.85) * ring * 1.2;

    // ---- frost crystals: voronoi cracks glinting with treble ----
    vec2 fq = p * 5.0 + fl * -0.35;
    vec2 fcell = floor(fq);
    float f1 = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 o = hash22(fcell + g);
            o = 0.5 + 0.4 * sin(t * 0.4 + 6.2831 * o);
            float d = length(g + o - fract(fq));
            f1 = min(f1, d);
        }
    }
    float crack = pow(1.0 - clamp(f1, 0.0, 1.0), 6.0);
    col += pal(0.55) * crack * (0.12 + uTreble * 0.9 + uBeat * uBeatResponse * 0.25);

    // ---- snowfall: three parallax particle layers riding the smear ----
    float snow = 0.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float depth = 1.0 - fi * 0.28;
        float scale = 7.0 + fi * 6.0;
        // Fall + wind drift; the smear field blows flakes around (nearer
        // layers react harder - the "mix it up" swirl).
        vec2 q = p * scale;
        q.y += t * (0.9 + fi * 0.5) * scale * 0.16;
        q.x += sin(t * (0.5 + fi * 0.2) + fi * 1.7) * (0.5 + uSway) * 1.2;
        q += fl * scale * (0.55 + 0.35 * depth) * -1.0;
        vec2 cell = floor(q);
        vec2 jitter = hash22(cell);
        vec2 fpos = fract(q) - (0.25 + 0.5 * jitter);
        float seed = hash12(cell * 1.63 + fi * 9.7);
        float size = (0.045 + 0.06 * seed) * (1.0 + uBass * uBeatResponse * 0.4);
        float flake = smoothstep(size, size * 0.25, length(fpos));
        // Treble makes flakes twinkle; each flake blinks on its own phase.
        float twinkle = 0.65 + 0.35 * sin(t * (2.0 + seed * 5.0) + seed * 40.0 + uTreble * 6.0);
        snow += flake * twinkle * depth * (0.55 + 0.45 * step(0.25, seed));
    }
    // Snow stays near-white with a hint of the palette so it reads as snow
    // on any color scheme.
    col += snow * mix(vec3(0.95), pal(0.6), 0.25) * (0.8 + uEnergy * 0.5);

    // Spectrum shimmer along the bottom "shoreline" - a quiet nod to the
    // audio without a literal bar display.
    float shore = smoothstep(0.15, -0.6, p.y);
    col += pal(0.35) * shore * aband(vUv.x) * 0.35;

    fragColor = vec4(grade(col), 1.0);
}
