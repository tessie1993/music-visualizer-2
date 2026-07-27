#version 300 es
// P1 display: plain dye presentation (soft rolloff for HDR dye). The full
// shading/bloom/sunrays/dither chain lands in the Look phase per v2 spec.
precision highp float;
in vec2 vUv;
uniform sampler2D uDye;
out vec4 fragColor;
void main() {
    vec3 c = texture(uDye, vUv).rgb;
    c = c / (1.0 + max(max(c.r, c.g), c.b) * 0.15);
    fragColor = vec4(c, 1.0);
}
