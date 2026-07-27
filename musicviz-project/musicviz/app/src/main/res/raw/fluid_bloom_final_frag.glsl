#version 300 es
// Ported from WebGL-Fluid-Simulation - MIT License, (c) 2017 Pavel Dobryakov
precision mediump float;
in vec2 vL;
in vec2 vR;
in vec2 vT;
in vec2 vB;
uniform sampler2D uTexture;
uniform float uIntensity;
out vec4 fragColor;
void main() {
    vec4 sum = texture(uTexture, vL) + texture(uTexture, vR) +
        texture(uTexture, vT) + texture(uTexture, vB);
    fragColor = sum * 0.25 * uIntensity;
}
