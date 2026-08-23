#version 300 es
precision highp float;
// GLSL ES 3.00 defaults fragment sampler2D to LOWP (range [-2,2), ~8
// fraction bits). uAudioTex is R32F; on GPUs honoring sampler precision
// (Mali) every read is clamped and quantized.
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

// The three lines every fragment style carries, in this order: the uniform
// block/aband/awave/view() first, then the palette, then grade() - which calls
// pal() and so must come after it.
//#include lib_scene_uniforms
//#include lib_palette
//#include lib_scene_grade
// The 3D toolkit and the touch helpers, already wired so a style author never
// has to edit this header. Both are pure functions over things declared above;
// an unused one is dropped by the linker and costs nothing.
//#include lib_sdf3
//#include lib_touch

// KIFS - filled in by the KIFS agent
//
// PLACEHOLDER. main() below is scaffolding - a palette-coloured radial field
// that compiles, grades and vignettes like any other style - so the id is
// selectable and the uniform audit passes before the style itself exists.
// Replace main(), and put whatever helpers it needs between this comment and
// it. The header above (version, precision, vUv/fragColor, the includes) is
// the shared contract: leave it exactly as it is.
void main() {
    vec2 uv = view();
    float r = length(uv);
    float f = r * 0.6 - uTime * 0.05 + uEnergy * 0.2;
    vec3 col = pal(f) * (0.35 + 0.5 * uEnergy + 0.4 * uBass);
    col *= smoothstep(1.6, 0.2, r);
    fragColor = vec4(grade(col), 1.0);
}
