#version 300 es
precision highp float;

// Shading for the Chladni plate. Two looks off one geometry pass:
//   uFlat = 0  the lit 3D surface, sand still marking its nodal lines;
//   uFlat = 1  the plate seen flat from above - the photograph of the
//              experiment, sand on metal.
//
// Colour here is palette IDENTITY only (base hue + span). Hue rotation,
// brightness, contrast and the colour cycle belong to the composite pass for
// every scene that has no grading pass of its own, this one included -
// applying them here as well would move the wheel twice per slider unit
// (see FluidHue's ownership rule).

in float vHeight;
in vec3 vNormal;
in vec3 vWorld;
in vec2 vPlate;

out vec4 fragColor;

uniform float uBaseHue;
uniform float uHueSpan;
uniform float uFlat;
uniform float uSand;        // "Sand" - nodal line weight
uniform float uHeightNorm;  // 1 / peak displacement, for colour normalization
uniform float uEnergy;
uniform float uTreble;
uniform float uTime;
uniform vec3 uEye;

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

/** Cheap value hash, for the grain that makes sand read as grains. */
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float h = vHeight;

    // Nodal lines, at constant width on screen whatever the gradient: the
    // sand collects where the plate does not move, and how wide that band is
    // depends on how steeply the surface passes through zero. fwidth() is
    // what keeps a coarse figure's lines as fine as a dense one's.
    float w = max(fwidth(h), 1e-5) * (0.8 + 2.2 * clamp(uSand, 0.0, 2.0));
    float d = abs(h) / w;
    float sand = exp(-d * d);
    // Grains, not a stroke: speckle the band, and let the treble glint on it.
    float grain = 0.72 + 0.28 * hash(floor(vPlate * 420.0));
    sand *= grain * (1.0 + 0.5 * clamp(uTreble, 0.0, 1.5));

    // Colour follows displacement, so the two phases of the standing wave sit
    // at opposite ends of the palette's span and the figure reads as a shape
    // rather than as a texture.
    float t = clamp(h * uHeightNorm * 0.5 + 0.5, 0.0, 1.0);
    float hue = fract(uBaseHue + uHueSpan * t);
    vec3 tint = hsv2rgb(vec3(hue, 0.85, 1.0));
    vec3 sandColor = mix(vec3(1.0), hsv2rgb(vec3(fract(uBaseHue + uHueSpan * 0.85), 0.5, 1.0)), 0.45);

    vec3 color;
    if (uFlat > 0.5) {
        // The flat view: dark metal, the standing wave as a faint field under
        // the sand, so a loud plate glows and a quiet one is nearly bare.
        float field = clamp(abs(h) * uHeightNorm, 0.0, 1.0);
        color = tint * (0.05 + 0.45 * field * (0.5 + 0.8 * clamp(uEnergy, 0.0, 1.5)));
    } else {
        vec3 n = normalize(vNormal);
        if (!gl_FrontFacing) n = -n;
        vec3 view = normalize(uEye - vWorld);
        vec3 light = normalize(vec3(0.45, 0.85, 0.30));
        float diffuse = max(dot(n, light), 0.0);
        float spec = pow(max(dot(n, normalize(light + view)), 0.0), 42.0);
        // Fresnel rim: the grazing edges of a metal plate catch the light,
        // and it is what gives the relief its silhouette against the table.
        float rim = pow(1.0 - max(dot(n, view), 0.0), 3.0);
        color = tint * (0.14 + 0.86 * diffuse) + vec3(spec) * 0.55 + tint * rim * 0.35;
    }

    color = mix(color, sandColor, clamp(sand, 0.0, 1.0));
    fragColor = vec4(color, 1.0);
}
