#version 300 es
precision mediump float;

in float vHue;
in float vEnergy;
out vec4 fragColor;

uniform float uSat;
uniform float uBright;
uniform float uInvert;

vec3 hsv2rgb(vec3 c) {
    vec4 k = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + k.xyz) * 6.0 - k.www);
    return c.z * mix(k.xxx, clamp(p - k.xxx, 0.0, 1.0), c.y);
}

void main() {
    vec2 d = gl_PointCoord - vec2(0.5);
    float r2 = dot(d, d);
    float alpha = exp(-r2 * 14.0) * (0.35 + 0.65 * vEnergy);
    vec3 color = hsv2rgb(vec3(vHue, (0.75 - 0.35 * vEnergy) * uSat, (0.6 + 0.4 * vEnergy) * uBright));
    color = mix(color, max(vec3(1.0) - color, 0.0), uInvert);
    fragColor = vec4(color * alpha, alpha);
}
