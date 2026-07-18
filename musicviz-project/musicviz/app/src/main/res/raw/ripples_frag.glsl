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

float aband(float x) { return texture(uAudioTex, vec2(clamp(x, 0.0, 1.0), 0.25)).r; }
float awave(float x) { return texture(uAudioTex, vec2(clamp(x, 0.0, 1.0), 0.75)).r; }

vec2 view() {
    vec2 uv = vUv * 2.0 - 1.0;
    uv.x *= uResolution.x / uResolution.y;
    if (uMirrorX > 0.5) uv.x = abs(uv.x);
    float a = uRotation;
    uv = mat2(cos(a), -sin(a), sin(a), cos(a)) * uv;
    float z = uZoom * pow(2.0, uZoomPhase) * (1.0 + uBeat * uBeatResponse * 0.15);
    uv /= max(z, 0.05);
    uv += uTurbulence * 0.06 * vec2(sin(uv.y * 6.0 + uTime), cos(uv.x * 6.0 + uTime * 1.3));
    return uv;
}

vec3 pal(float t) {
    return 0.5 + 0.5 * cos(6.2831 * (uPalBase + uColorShift + t * uPalRange * uHueRange + vec3(0.0, 0.33, 0.67)));
}

vec3 grade(vec3 col) {
    float g = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(g), col, uSat) * uBright * uIntensity;
    return mix(col, max(vec3(1.0) - col, 0.0), uInvert);
}
// Interference ripples: bass drives standing waves, beats fire rings.
void main() {
    vec2 p = view();
    float r = length(p);
    float t = uTime;
    float wave = sin(r * (24.0 + uBass * 14.0) - t * 5.0) * exp(-r * 1.6);
    vec2 c2 = vec2(sin(t * 0.4), cos(t * 0.3)) * 0.5;
    float r2 = length(p - c2);
    wave += sin(r2 * 30.0 - t * 6.0) * exp(-r2 * 2.2) * uMid * 2.0;
    float ring = exp(-abs(r - fract(t * 0.7) * 1.6) * 14.0) * uBeat * uBeatResponse * 1.5;
    float v = wave * (0.4 + uEnergy * 1.2) + ring;
    vec3 col = pal(0.5 + v * 0.4) * (abs(v) * 1.3 + ring);
    fragColor = vec4(grade(col), 1.0);
}
