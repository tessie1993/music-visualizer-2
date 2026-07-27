#version 300 es
// Ported from WebGL-Fluid-Simulation - MIT License, (c) 2017 Pavel Dobryakov
// Occlusion mask for the god-ray march: bright dye blocks the light.
precision mediump float;
in vec2 vUv;
uniform sampler2D uTexture;
out vec4 fragColor;
void main() {
    vec4 c = texture(uTexture, vUv);
    float br = max(c.r, max(c.g, c.b));
    c.a = 1.0 - min(max(br * 20.0, 0.0), 0.8);
    fragColor = c;
}
