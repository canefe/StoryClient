#version 150

in vec4 Position;

uniform mat4 ProjMat;
uniform vec2 InSize;
uniform vec2 OutSize;

out vec2 texCoord;

void main() {
    vec4 outPos = ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);

    // No Y-flip here: this pass reads minecraft:main and writes to the swap
    // target, and the following vanilla blit pass already flips on its way back
    // to main. Flipping in both passes would render the world upside-down.
    vec2 sizeRatio = OutSize / InSize;
    texCoord = Position.xy / OutSize;
    texCoord.x = texCoord.x * sizeRatio.x;
    texCoord.y = texCoord.y * sizeRatio.y;
}
