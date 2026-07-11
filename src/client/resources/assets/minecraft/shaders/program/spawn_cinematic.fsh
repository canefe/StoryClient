#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Strength; // 0..1 overall effect mix (fades with the cinematic)
uniform float Time;     // seconds since the cinematic started (subtle animation)

in vec2 texCoord;
out vec4 fragColor;

// GTA-V-style "switch" grade: green/sepia wash, heavy vignette, soft bloom and a
// radial edge blur (sharp centre, blurry frame — a cheap tilt-shift / lens feel).
// Single pass: the "bloom" is a small bright-pass box blur, good enough to read as
// glow without a separate downsample chain.

const vec3 TINT      = vec3(0.78, 0.92, 0.55); // green-gold wash
const float TINT_AMT = 0.42;                    // how hard the wash pulls
const float VIGNETTE = 1.15;                    // dark-corner strength
const float BLOOM_AMT = 0.55;                   // glow contribution
const float BLOOM_THRESH = 0.62;                // brightness above which things glow
const float EDGE_BLUR = 2.6;                    // max blur radius (px) at the frame edge
const float DESAT    = 0.25;                    // pull a little colour out first

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// Cheap blurred sample: an 8-tap ring at the given radius (in pixels).
vec3 ringSample(vec2 uv, float radiusPx) {
    vec2 px = radiusPx / InSize;
    vec3 sum = texture(DiffuseSampler, uv).rgb;
    sum += texture(DiffuseSampler, uv + vec2( px.x,  0.0)).rgb;
    sum += texture(DiffuseSampler, uv + vec2(-px.x,  0.0)).rgb;
    sum += texture(DiffuseSampler, uv + vec2( 0.0,  px.y)).rgb;
    sum += texture(DiffuseSampler, uv + vec2( 0.0, -px.y)).rgb;
    sum += texture(DiffuseSampler, uv + px * 0.7071).rgb;
    sum += texture(DiffuseSampler, uv - px * 0.7071).rgb;
    sum += texture(DiffuseSampler, uv + vec2(px.x, -px.y) * 0.7071).rgb;
    sum += texture(DiffuseSampler, uv + vec2(-px.x, px.y) * 0.7071).rgb;
    return sum / 9.0;
}

void main() {
    vec2 uv = texCoord;

    // Distance from centre drives both the vignette and the edge blur.
    vec2 d = uv - vec2(0.5);
    float dist = length(d) * 1.41421356; // 0 at centre, ~1 at corners

    // --- Edge blur: sharp centre, blurry frame ---
    float blurRadius = EDGE_BLUR * smoothstep(0.35, 1.0, dist);
    vec3 base = ringSample(uv, blurRadius);

    // --- Bloom: blurred bright-pass added back on top ---
    vec3 wide = ringSample(uv, max(blurRadius, 3.5));
    vec3 bright = max(wide - vec3(BLOOM_THRESH), vec3(0.0)) / (1.0 - BLOOM_THRESH);
    vec3 bloom = bright * BLOOM_AMT;

    vec3 col = base + bloom;

    // --- Grade: slight desat, then green/sepia wash ---
    float l = luma(col);
    col = mix(col, vec3(l), DESAT);
    col = mix(col, l * TINT * 1.25, TINT_AMT);

    // --- Vignette ---
    float vig = 1.0 - VIGNETTE * dist * dist;
    // A faint breathing flicker so it reads as a live lens, not a static gradient.
    vig *= 1.0 - 0.03 * sin(Time * 6.2831 * 0.4);
    col *= clamp(vig, 0.0, 1.0);

    // Mix the whole graded result against the untouched frame by Strength so the
    // filter fades in/out with the cinematic.
    vec3 original = texture(DiffuseSampler, uv).rgb;
    fragColor = vec4(mix(original, col, clamp(Strength, 0.0, 1.0)), 1.0);
}
