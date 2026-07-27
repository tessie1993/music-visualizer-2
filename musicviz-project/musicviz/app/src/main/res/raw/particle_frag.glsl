#version 300 es
precision mediump float;

in float vHue;
in float vEnergy;
out vec4 fragColor;

uniform float uSat;
uniform float uBright;
uniform float uContrast;
uniform float uGamma;
uniform float uShape;

vec3 hsv2rgb(vec3 c) {
    vec4 k = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + k.xyz) * 6.0 - k.www);
    return c.z * mix(k.xxx, clamp(p - k.xxx, 0.0, 1.0), c.y);
}

float shapeMask(vec2 d, float shape) {
    float r = length(d);
    if (shape < 0.5) {
        // Dot: soft gaussian.
        return exp(-dot(d, d) * 14.0);
    } else if (shape < 1.5) {
        // Ring.
        return exp(-abs(r - 0.34) * 26.0);
    } else if (shape < 2.5) {
        // Star: angular petals.
        float a = atan(d.y, d.x);
        float pet = 0.32 + 0.12 * cos(a * 5.0);
        return exp(-abs(r - pet) * 20.0);
    } else if (shape < 3.5) {
        // Square.
        float m = max(abs(d.x), abs(d.y));
        return smoothstep(0.42, 0.34, m);
    } else if (shape < 4.5) {
        // Spark: cross.
        float cross = min(abs(d.x), abs(d.y));
        return exp(-cross * 60.0) * smoothstep(0.5, 0.1, r);
    } else if (shape < 5.5) {
        // Hex: flat-top hexagon.
        vec2 q = abs(d);
        float hex = max(dot(q, normalize(vec2(1.0, 1.7320508))), q.x);
        return smoothstep(0.4, 0.33, hex);
    } else {
        // Bubble: thin bright shell with a faint fill.
        return exp(-abs(r - 0.38) * 40.0) + smoothstep(0.4, 0.0, r) * 0.15;
    }
}

void main() {
    vec2 d = gl_PointCoord - vec2(0.5);
    float alpha = shapeMask(d, uShape) * (0.35 + 0.65 * vEnergy);
    vec3 color = hsv2rgb(vec3(vHue, (0.75 - 0.35 * vEnergy) * uSat, (0.6 + 0.4 * vEnergy) * uBright));
    color = (color - 0.5) * uContrast + 0.5;
    color = pow(max(color, 0.0), vec3(1.0 / max(uGamma, 0.05)));
    // Invert is owned by the composite pass (uPostInvert) so it is
    // applied exactly once to the whole frame.
    fragColor = vec4(color * alpha, alpha);
}
