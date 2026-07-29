#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uTex;
uniform float uZoom;
uniform float uRotation;
uniform float uZoomPhase;
uniform float uMirrorX;
uniform float uHue;
uniform float uSat;
uniform float uBright;
uniform float uContrast;
uniform float uGamma;
uniform float uInvert;
uniform float uIntensity;

vec3 hueRotate(vec3 c, float a) {
    const vec3 w = vec3(0.299, 0.587, 0.114);
    float angle = a * 6.2831;
    float cs = cos(angle);
    float sn = sin(angle);
    return vec3(dot(c, w)) + (c - vec3(dot(c, w))) * cs + cross(vec3(0.57735), c) * sn;
}

void main() {
    vec2 uv = vUv - 0.5;
    if (uMirrorX > 0.5) uv.x = abs(uv.x);
    float a = uRotation;
    uv = mat2(cos(a), -sin(a), sin(a), cos(a)) * uv;
    // Triangle-wave exponent: 1x -> 2x -> 1x smoothly, so the endless-zoom
    // phase wrap never causes a visible scale pop (2^1 snapping to 2^0).
    float z = uZoom * pow(2.0, 1.0 - abs(2.0 * uZoomPhase - 1.0));
    uv = uv / max(z, 0.05) + 0.5;
    vec3 col = texture(uTex, clamp(uv, 0.0, 1.0)).rgb;
    col = hueRotate(col, uHue);
    float g = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(g), col, uSat);
    col = (col - 0.5) * uContrast + 0.5;
    col = pow(max(col, 0.0), vec3(1.0 / max(uGamma, 0.05)));
    col *= uBright * uIntensity;
    col = mix(col, max(vec3(1.0) - col, 0.0), uInvert);
    fragColor = vec4(col, 1.0);
}
