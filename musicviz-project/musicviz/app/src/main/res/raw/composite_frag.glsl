#version 300 es
precision mediump float;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uTexA;
uniform sampler2D uTexB;
uniform float uProgress;
uniform int uStyle;
uniform float uTime;
uniform float uBeat;
uniform float uChroma;
uniform float uVignette;
uniform float uScanline;
uniform float uGrain;
uniform float uGlitch;
uniform float uFisheye;
uniform float uStrobe;
// Universal geometric + color post effects, applied here so they work on
// EVERY scene type - including particle scenes, whose vertex pipeline can't
// honor screen-space shape params on its own.
uniform float uPostWarp;
uniform float uPostRipple;
uniform float uPostSymmetry;
uniform float uPostKaleido;
uniform float uPostPixelate;
uniform float uPostTile;
uniform float uPostTwist;
uniform float uPostMirror;
uniform float uPostBloom;
uniform float uPostPosterize;
uniform float uPostInvert;
// Motion + color params that particle/milkdrop pipelines can't honor natively
// (shader scenes do these in-shader; the uploads pass 0 for them).
uniform float uPostDriftX;
uniform float uPostDriftY;
uniform float uPostSway;
uniform float uPostShake;
uniform float uPostFlash;
uniform float uPostTemp;
uniform float uPostSolarize;
// FlowField fluidWarp: the shared fluid velocity field bends the sampling
// coordinate of ANY scene's output (particles, shaders, milkdrop) before the
// scene-texture fetch. A 1x1 zero texture is bound when disabled so the
// sampler is always valid.
uniform highp sampler2D uFlow;   // half-float velocities exceed lowp range
uniform float uFlowStrength;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Applies geometric transforms to a [0,1] uv, operating in centered space.
vec2 geo(vec2 uv) {
    vec2 c = uv - 0.5;
    if (abs(uPostSway) > 0.001) {
        float sa = uPostSway * 0.35 * sin(uTime * 0.7);
        c = mat2(cos(sa), -sin(sa), sin(sa), cos(sa)) * c;
    }
    if (uPostShake > 0.001) {
        c += uPostShake * uBeat * 0.03 * vec2(sin(uTime * 91.7), cos(uTime * 77.3));
    }
    if (uPostMirror > 0.5) c.x = abs(c.x);
    if (uPostKaleido > 0.5 && uPostSymmetry >= 2.0) {
        float ang = atan(c.y, c.x);
        float rad = length(c);
        float seg = 6.2831853 / uPostSymmetry;
        ang = abs(mod(ang, seg) - seg * 0.5);
        c = vec2(cos(ang), sin(ang)) * rad;
    }
    if (abs(uPostTwist) > 0.001) {
        float tr = length(c) * uPostTwist * 2.0;
        c = mat2(cos(tr), -sin(tr), sin(tr), cos(tr)) * c;
    }
    if (uPostWarp > 0.001) {
        c += uPostWarp * 0.06 * vec2(sin(c.y * 8.0 + uTime), cos(c.x * 8.0 + uTime * 1.2));
    }
    if (uPostRipple > 0.001) {
        float r = length(c);
        c *= 1.0 + uPostRipple * 0.12 * sin(r * 16.0 - uTime * 3.0);
    }
    vec2 p = c + 0.5;
    if (abs(uPostDriftX) + abs(uPostDriftY) > 0.001) {
        // Wrap so the image scrolls instead of smearing at the clamped edge.
        p = fract(p + vec2(uPostDriftX, uPostDriftY) * uTime * 0.1);
    }
    if (uPostTile > 1.01) {
        p = fract(p * uPostTile);
    }
    if (uPostPixelate > 0.001) {
        float px = mix(512.0, 40.0, uPostPixelate);
        p = (floor(p * px) + 0.5) / px;
    }
    return p;
}

// Screen-space FX chain applied to the final composited frame, so it works
// identically for shader, particle and milkdrop scenes and during
// transitions. Order: geometry -> distortion (fisheye, glitch) -> sample
// (chromatic aberration) -> shading (posterize, scanlines, grain, vignette,
// bloom, strobe, invert).
vec3 postFx(sampler2D tex, vec2 uv, vec3 fallback) {
    vec2 p = geo(uv);
    if (uFlowStrength > 0.001) {
        // Soft-limit the field to the +-6 range the 0.015 scale was tuned
        // for: emitter velocities reach ~36 (splat force x audio), and an
        // unbounded warp displaces the fetch by half the screen per frame -
        // perceived as full-screen colour flashing, not a fluid bend.
        vec2 flow = texture(uFlow, p).xy;
        flow *= 6.0 / (6.0 + length(flow));
        p -= flow * uFlowStrength * 0.015;
    }
    if (abs(uFisheye) > 0.001) {
        vec2 c = p - 0.5;
        float r = length(c) * 2.0;
        p = 0.5 + c * (1.0 + uFisheye * 0.6 * (r * r - 0.5));
    }
    if (uGlitch > 0.001) {
        float band = floor(p.y * 24.0);
        float jump = hash12(vec2(band, floor(uTime * 12.0)));
        if (jump > 1.0 - uGlitch * 0.35 * (0.4 + uBeat)) {
            p.x += (hash12(vec2(band, uTime)) - 0.5) * uGlitch * 0.3;
        }
    }
    vec3 col;
    if (uChroma > 0.001) {
        vec2 dir = (p - 0.5) * uChroma * 0.03;
        col.r = texture(tex, clamp(p + dir, 0.0, 1.0)).r;
        col.g = texture(tex, clamp(p, 0.0, 1.0)).g;
        col.b = texture(tex, clamp(p - dir, 0.0, 1.0)).b;
    } else {
        col = texture(tex, clamp(p, 0.0, 1.0)).rgb;
    }
    if (uPostBloom > 0.001) col += uPostBloom * col * col;
    if (uPostPosterize > 0.001) {
        float levels = mix(24.0, 3.0, uPostPosterize);
        col = floor(col * levels + 0.5) / levels;
    }
    if (uScanline > 0.001) {
        col *= 1.0 - uScanline * 0.35 * (0.5 + 0.5 * sin(p.y * 900.0));
    }
    if (uGrain > 0.001) {
        col += (hash12(p * 1913.0 + uTime) - 0.5) * uGrain * 0.25;
    }
    if (uVignette > 0.001) {
        float d = length(uv - 0.5) * 1.4142;
        col *= 1.0 - uVignette * smoothstep(0.5, 1.05, d);
    }
    if (uStrobe > 0.001) {
        col *= 1.0 - uStrobe * 0.85 * step(0.5, fract(uTime * 9.0)) * (1.0 - uBeat * 0.5);
    }
    col.r += uPostTemp * 0.12;
    col.b -= uPostTemp * 0.12;
    if (uPostSolarize > 0.5) col = abs(1.0 - 2.0 * col);
    col += uPostFlash * uBeat * 0.6;
    if (uPostInvert > 0.5) col = max(vec3(1.0) - col, 0.0);
    return col;
}

// Every sample below goes through postFx, INCLUDING the outgoing texture:
// sampling uTexB raw made the whole FX chain (vignette, chroma, scanlines,
// kaleido, bloom...) pop off on the old scene for the entire transition and
// snap back at the end - perceived as a flash on every preset/scene switch.
// SLIDE/ZOOM previously resampled BOTH textures raw, dropping FX everywhere.
void main() {
    vec3 a = postFx(uTexA, vUv, vec3(0.0));
    if (uStyle == 0 || uProgress >= 1.0) {
        fragColor = vec4(a, 1.0);
        return;
    }
    if (uStyle == 1) {
        vec3 b = postFx(uTexB, vUv, vec3(0.0));
        fragColor = vec4(mix(b, a, uProgress), 1.0);
    } else if (uStyle == 3) {
        float sh = smoothstep(0.0, 1.0, uProgress);
        vec3 aSlide = postFx(uTexA, clamp(vUv + vec2(1.0 - sh, 0.0), 0.0, 1.0), vec3(0.0));
        vec3 bSlide = postFx(uTexB, clamp(vUv - vec2(sh, 0.0), 0.0, 1.0), vec3(0.0));
        fragColor = vec4(vUv.x < sh ? aSlide : bSlide, 1.0);
    } else if (uStyle == 4) {
        float sh = smoothstep(0.0, 1.0, uProgress);
        vec2 zUv = (vUv - 0.5) / (1.0 + sh * 2.5) + 0.5;
        vec3 bZoom = postFx(uTexB, clamp(zUv, 0.0, 1.0), vec3(0.0));
        vec2 aUv = (vUv - 0.5) * (1.0 + (1.0 - sh) * 0.35) + 0.5;
        vec3 aZoom = postFx(uTexA, clamp(aUv, 0.0, 1.0), vec3(0.0));
        fragColor = vec4(mix(bZoom, aZoom, sh), 1.0);
    } else {
        float lumB = dot(texture(uTexB, vUv).rgb, vec3(0.299, 0.587, 0.114));
        vec2 melted = vUv + vec2(0.0, uProgress * (0.25 + lumB * 0.6));
        vec3 bMelt = postFx(uTexB, clamp(melted, 0.0, 1.0), vec3(0.0));
        float reveal = smoothstep(0.0, 1.0, uProgress * 1.4 - lumB * 0.4);
        fragColor = vec4(mix(bMelt, a, clamp(reveal, 0.0, 1.0)), 1.0);
    }
}
