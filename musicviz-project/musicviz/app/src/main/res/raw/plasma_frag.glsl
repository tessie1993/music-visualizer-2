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

// Classic demo-scene plasma, breathing with the mix.
void main() {
    vec2 uv = view() * 2.0;
    float t = uTime * 0.6;
    float v = sin(uv.x * 2.0 + t) + sin(uv.y * 2.3 - t * 1.2)
        + sin((uv.x + uv.y) * 1.7 + t * 0.7)
        + sin(length(uv) * (3.0 + uBass * 3.0) - t * 2.0);
    v = v / 4.0 + aband(fract(v * 0.25 + 0.5)) * 0.4;
    vec3 col = pal(v) * (0.45 + uEnergy * 0.9 + uBeat * uBeatResponse * 0.2);
    fragColor = vec4(grade(col), 1.0);
}
