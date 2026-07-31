#version 330

#define Width 3

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform OutlineConfig {
    int RenderMode;
    float FillOpacity;
};

in vec2 texCoord;

out vec4 fragColor;

float quad(float x) {
    return x * x;
}

void main() {
    vec2 oneTexel = 1.0 / InSize;
    float divider = 5;
    float maxSample = 3;
    vec4 current = texture(InSampler, texCoord);

    if (current.a != 0) {
        if (RenderMode == 1) discard;
        fragColor = vec4(current.rgb, current.a * FillOpacity);
    } else {
        if (RenderMode == 0) discard;
        float alpha = 0;

        for (float x = -Width; x < Width; x++) {
            for (float y = -Width; y < Width; y++) {
                vec4 texel = texture(InSampler, texCoord + vec2(x, y) * oneTexel);

                if (texel.a != 0) {
                    current = texel;
                    alpha += max(0, (maxSample - distance(vec2(x, y), vec2(0))) / divider);
                }
            }
        }

        fragColor = vec4(current.rgb, quad(alpha));
    }
}
