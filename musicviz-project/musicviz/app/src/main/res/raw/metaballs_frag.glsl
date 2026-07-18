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
// Metaballs sized by band energy, drifting to the music.
void main() {
    vec2 p = view();
    float t = uTime * 0.6;
    float field = 0.0;
    float hueAcc = 0.0;
    for (int i = 0; i < 7; i++) {
        float fi = float(i) / 7.0;
        float b = aband(fi);
        vec2 c = 0.75 * vec2(sin(t * (0.5 + fi) + fi * 6.28), cos(t * (0.7 + fi * 0.5) + fi * 4.0));
        float radius = 0.05 + b * 0.22;
        float d = max(length(p - c), 0.001);
        float contrib = radius * radius / (d * d);
        field += contrib;
        hueAcc += fi * contrib;
    }
    float hue = hueAcc / max(field, 0.001);
    float body = smoothstep(0.9, 1.15, field);
    float rim = smoothstep(0.9, 1.0, field) - smoothstep(1.05, 1.3, field);
    vec3 col = pal(hue) * body * (0.5 + uEnergy) + vec3(1.0) * max(rim, 0.0) * (0.4 + uBeat * uBeatResponse * 0.5);
    fragColor = vec4(grade(col), 1.0);
}
