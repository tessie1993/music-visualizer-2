#version 300 es
precision mediump float;

in vec2 vUv;
out vec4 fragColor;

uniform float uFadeAlpha;

void main() {
    fragColor = vec4(0.02, 0.01, 0.05, uFadeAlpha);
}
