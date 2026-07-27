#version 300 es
// Speed-colored soft points, drawn additively over the dye.
precision highp float;
in highp float vSpeed;
uniform highp float uHueBase;
uniform highp float uHueSpan;
uniform highp float uBrightness;
out vec4 fragColor;
vec3 hsv(float h, float s, float v) {
    vec3 k = mod(vec3(5.0, 3.0, 1.0) + h * 6.0, 6.0);
    return v - v * s * clamp(min(k, 4.0 - k), 0.0, 1.0);
}
void main() {
    vec2 d = gl_PointCoord * 2.0 - 1.0;
    float m = 1.0 - clamp(dot(d, d), 0.0, 1.0);
    if (m <= 0.0) discard;
    float sp = clamp(vSpeed * 1.8, 0.0, 1.0);
    vec3 c = hsv(fract(uHueBase + sp * uHueSpan), 0.75 - sp * 0.35, 1.0);
    fragColor = vec4(c * (m * m) * uBrightness * (0.25 + sp), 1.0);
}
