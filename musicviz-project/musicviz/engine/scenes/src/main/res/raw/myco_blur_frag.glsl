#version 300 es
precision highp float;

// MYCELIUM - trail diffusion and decay: a 3x3 box mean with the decay folded
// into the same pass (the reference machine's arrangement). Diffusion is what
// turns yesterday's paths into a soft gradient agents can smell from afar;
// decay is what lets an abandoned road die.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uTrail;
uniform vec2 uTrailRes;
uniform float uDecay; // survival per frame, < 1

void main() {
    vec2 texel = 1.0 / uTrailRes;
    vec2 sum = vec2(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            sum += texture(uTrail, fract(vUv + vec2(float(x), float(y)) * texel)).rg;
        }
    }
    vec2 blurred = sum / 9.0 * uDecay;
    // Sanitize the loop: additive deposits + feedback must never keep a NaN.
    blurred = clamp(blurred, vec2(0.0), vec2(64.0));
    fragColor = vec4(blurred, 0.0, 1.0);
}
