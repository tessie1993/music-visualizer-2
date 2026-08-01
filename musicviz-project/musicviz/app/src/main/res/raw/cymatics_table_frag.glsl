#version 300 es
precision highp float;

// The table the plate sits on: a very dark radial wash in the palette's own
// hue, drawn before the plate so the square figure reads as an object on a
// surface instead of as a bright shape floating in a black void.
//
// Deliberately dim - it is the room, not the visual. It breathes with the
// track's level so silence really does go dark.

in vec2 vUv;
out vec4 fragColor;

uniform vec2 uResolution;
uniform float uBaseHue;
uniform float uHueSpan;
uniform float uEnergy;

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

void main() {
    vec2 uv = vUv * 2.0 - 1.0;
    uv.x *= uResolution.x / max(uResolution.y, 1.0);
    float r = length(uv);
    float glow = exp(-r * r * 1.15);
    vec3 tint = hsv2rgb(vec3(fract(uBaseHue + uHueSpan * 0.25), 0.7, 1.0));
    fragColor = vec4(tint * glow * (0.035 + 0.075 * clamp(uEnergy, 0.0, 1.5)), 1.0);
}
