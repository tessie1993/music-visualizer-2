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

// Synthwave horizon: perspective floor grid pumping with bass.
void main() {
    vec2 uv = view();
    vec3 col = vec3(0.0);
    if (uv.y < 0.0) {
        float pz = 1.0 / (-uv.y + 0.05);
        vec2 g = vec2(uv.x * pz, pz + uTime * (2.0 + uBass * 6.0));
        float line = max(exp(-abs(fract(g.x) - 0.5) * 24.0), exp(-abs(fract(g.y) - 0.5) * 24.0));
        float h = aband(fract(abs(g.x) * 0.12));
        col = pal(0.75) * line * exp(-pz * 0.12) * (0.5 + h * 1.4);
    } else {
        float sunR = 0.35 + uBass * uBeatResponse * 0.1;
        float d = length(uv - vec2(0.0, 0.28));
        float sun = smoothstep(sunR, sunR - 0.02, d) * step(fract(uv.y * 24.0 + uTime * 0.5), 0.8);
        col = pal(uv.y * 0.6) * sun + pal(0.05) * exp(-d * 3.0) * 0.4;
    }
    fragColor = vec4(grade(col), 1.0);
}
