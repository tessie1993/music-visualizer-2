#version 300 es
precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uPrev;
uniform float uDecay;
uniform float uZoomWarp;
uniform float uRotWarp;
uniform float uHueRot;
uniform float uChroma;

vec3 hueRotate(vec3 c, float angle) {
    const mat3 toYiq = mat3(0.299, 0.596, 0.211, 0.587, -0.274, -0.523, 0.114, -0.322, 0.312);
    const mat3 toRgb = mat3(1.0, 1.0, 1.0, 0.956, -0.272, -1.106, 0.621, -0.647, 1.703);
    vec3 yiq = toYiq * c;
    float s = sin(angle);
    float co = cos(angle);
    yiq.yz = mat2(co, -s, s, co) * yiq.yz;
    return clamp(toRgb * yiq, 0.0, 1.0);
}

void main() {
    vec2 centered = vUv - 0.5;
    float s = sin(uRotWarp);
    float c = cos(uRotWarp);
    vec2 warped = mat2(c, -s, s, c) * centered * (1.0 - uZoomWarp);
    vec2 uv = warped + 0.5;
    vec2 fringe = normalize(centered + vec2(1e-4)) * uChroma * 0.004;
    vec4 base = texture(uPrev, uv);
    float r = texture(uPrev, uv + fringe).r;
    float b = texture(uPrev, uv - fringe).b;
    vec3 rgb = hueRotate(vec3(r, base.g, b), uHueRot);
    fragColor = vec4(rgb, base.a) * uDecay;
}
