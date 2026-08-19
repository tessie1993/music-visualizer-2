#version 300 es
precision highp float;

// MYCELIUM - the agent pass. One texel = one agent: (x, y, heading, species),
// positions in [0,1) toroidal trail space. The heading is STORED AS A TURN
// (heading / TAU, in [0,1)) rather than in radians: the agent texture rides
// RGBA8 on devices with no renderable float format, where a normalized
// texel clamps to [0,1] and a radian heading above 1 collapsed the whole
// population onto one bearing. Every channel now fits any format by
// construction; the pass decodes to radians on read and encodes on write.
//
// The rule is the published Physarum machine, reimplemented: sense the trail
// at three probes (ahead, +/- sensor angle at sensor distance), turn by the
// classic table - straight when ahead is strongest, a random turn when ahead
// is weakest, otherwise toward the stronger side - then step forward and
// wrap. Two species sense a COMBINED field through a 2x2 matrix (self and
// cross weights), which is where rivalry, symbiosis and predation live.
//
// Sensing is compressed (1 - exp(-k*t)) so saturated highways stop out-
// shouting faint new veins, and the sensor distance BREATHES on the beat -
// agents look further on the hit, which visibly re-organizes the network in
// rhythm without teleporting anything.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uAgents;
uniform sampler2D uTrail;
uniform vec2 uTrailRes;
uniform float uSensorDist;   // texels of trail space
uniform float uSensorAngle;  // radians
uniform float uTurnAngle;    // radians
uniform float uMoveStep;     // texels per frame
uniform vec4 uMatrix;        // (A<-A, A<-B, B<-A, B<-B) sense weights
uniform float uBreath;       // beat envelope onto sensor distance
uniform float uJitter;       // heading noise, radians
uniform float uSnap;         // >0: headings quantized to this many radians
uniform float uReaim;        // beat scatter: fraction of agents re-aimed
uniform float uTime;
uniform float uAniso;        // vertical sensing bias for frost-like growth
uniform float uInit;         // 1 = write a fresh random population and stop
uniform float uSpeciesMix;   // fraction of agents born as species B

const float TAU = 6.2831853;

float hash1(vec2 q) {
    return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453);
}

vec2 hash2(vec2 q) {
    q = vec2(dot(q, vec2(127.1, 311.7)), dot(q, vec2(269.5, 183.3)));
    return fract(sin(q) * 43758.5453);
}

float senseAt(vec2 pos, float species) {
    vec2 t = texture(uTrail, fract(pos)).rg;
    // Compressed sensing, tuned to the field's real levels: steady trunks sit
    // in the tens after additive deposit, and a constant that saturates there
    // makes every probe read alike - all ties, all agents walking straight,
    // and the network hardens into a rectangular grid. At 0.18 the trunks
    // still differ from the veins and the turning stays organic.
    t = 1.0 - exp(-0.18 * t);
    return species < 0.5 ? dot(t, uMatrix.xy) : dot(t, uMatrix.zw);
}

void main() {
    if (uInit > 0.5) {
        // Fresh population: uniform positions, uniform headings, species by
        // the declared mix - the reference machine's uniform-noise start.
        vec2 h = hash2(vUv * 719.7);
        // Already a turn in [0,1): the storage encoding, no TAU here.
        float heading0 = hash1(vUv * 149.3);
        float species0 = step(1.0 - uSpeciesMix, hash1(vUv * 449.1 + 3.3));
        fragColor = vec4(h, heading0, species0);
        return;
    }
    vec4 a = texture(uAgents, vUv);
    vec2 pos = a.xy;
    float heading = a.z * TAU; // stored as a turn; the math runs in radians
    float species = a.w;

    vec2 texel = 1.0 / uTrailRes;
    float dist = uSensorDist * (1.0 + 0.45 * uBreath);
    vec2 stretch = vec2(1.0, 1.0 + uAniso); // frost styles look further up/down

    vec2 fwd = vec2(cos(heading), sin(heading));
    vec2 lft = vec2(cos(heading + uSensorAngle), sin(heading + uSensorAngle));
    vec2 rgt = vec2(cos(heading - uSensorAngle), sin(heading - uSensorAngle));
    float sC = senseAt(pos + fwd * stretch * dist * texel, species);
    float sL = senseAt(pos + lft * stretch * dist * texel, species);
    float sR = senseAt(pos + rgt * stretch * dist * texel, species);

    float rnd = hash1(vUv * 977.0 + fract(uTime) * 61.7 + species);
    // Per-agent turn personality: identical turn angles synchronize the
    // whole population onto one heading lattice, and the network crystallizes
    // into right angles. A fixed +-20% spread per agent keeps the machine
    // deterministic per agent while the colony stays organic.
    float turn = uTurnAngle * (0.8 + 0.4 * hash1(vUv * 57.31));
    if (sC >= sL && sC >= sR) {
        // straight on
    } else if (sC < sL && sC < sR) {
        heading += (rnd < 0.5 ? -1.0 : 1.0) * turn;
    } else if (sL > sR) {
        heading += turn;
    } else {
        heading -= turn;
    }
    heading += (rnd - 0.5) * uJitter;

    // Beat scatter: a hashed subset of agents re-aims outward from centre,
    // so a drop reads as a spore burst rather than a global twitch.
    if (uReaim > 0.0 && hash1(vUv * 313.0 + 7.7) < uReaim) {
        vec2 fromCentre = pos - 0.5;
        heading = atan(fromCentre.y, fromCentre.x) + (rnd - 0.5) * 0.8;
    }

    if (uSnap > 0.0) heading = floor(heading / uSnap + 0.5) * uSnap;
    heading = mod(heading, TAU);

    pos = fract(pos + vec2(cos(heading), sin(heading)) * uMoveStep * texel);

    // Encode back to a turn: mod above keeps heading / TAU inside [0,1), so
    // the write survives a normalized RGBA8 target unclamped.
    fragColor = vec4(pos, heading / TAU, species);
}
