#version 330

// Simple box blur over an ISOLATED star-only capture (see StarCapture/SkyRendererMixin) -- no
// brightness/chroma threshold needed at all, unlike the old whole-screen version: nothing but
// star pixels (and transparent background) can possibly be in this buffer.

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform StarGlowConfig {
    vec4 Params;
};
#define Intensity Params.x

#define Width 4

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 oneTexel = 1.0 / InSize;

    vec4 sum = vec4(0.0);
    float weight = 0.0;

    for (float x = -Width; x <= Width; x++) {
        for (float y = -Width; y <= Width; y++) {
            float d = length(vec2(x, y));
            if (d > float(Width)) continue;

            float w = 1.0 - d / float(Width);
            sum += texture(InSampler, texCoord + vec2(x, y) * oneTexel) * w;
            weight += w;
        }
    }

    vec4 blurred = weight > 0.0 ? sum / weight : vec4(0.0);
    vec4 original = texture(InSampler, texCoord);

    fragColor = vec4(max(original.rgb, blurred.rgb * Intensity), max(original.a, blurred.a));
}
