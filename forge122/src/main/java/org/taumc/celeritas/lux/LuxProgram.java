package org.taumc.celeritas.lux;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.util.HashMap;
import java.util.Map;

/** Minimal GLSL program wrapper (GLSL 1.20, works on old Intel/AMD/NVIDIA drivers). */
final class LuxProgram {
    private final int id;
    private final Map<String, Integer> uniforms = new HashMap<>();

    LuxProgram(String name, String vertexSource, String fragmentSource) {
        int vs = compile(GL20.GL_VERTEX_SHADER, vertexSource, name + ".vsh");
        int fs;
        try {
            fs = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource, name + ".fsh");
        } catch (RuntimeException e) {
            GL20.glDeleteShader(vs);
            throw e;
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glLinkProgram(program);
        boolean ok = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_FALSE;
        String log = ok ? "" : GL20.glGetProgramInfoLog(program, 8192);
        GL20.glDetachShader(program, vs);
        GL20.glDetachShader(program, fs);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (!ok) {
            GL20.glDeleteProgram(program);
            throw new IllegalStateException("Failed to link " + name + ": " + log);
        }
        this.id = program;
    }

    private static int compile(int type, String source, String name) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 8192);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile " + name + ": " + log);
        }
        return shader;
    }

    void bind() {
        GL20.glUseProgram(id);
    }

    static void unbind() {
        GL20.glUseProgram(0);
    }

    int loc(String uniform) {
        Integer cached = uniforms.get(uniform);
        if (cached == null) {
            cached = GL20.glGetUniformLocation(id, uniform);
            uniforms.put(uniform, cached);
        }
        return cached;
    }

    void set1i(String name, int v) { GL20.glUniform1i(loc(name), v); }
    void set1f(String name, float v) { GL20.glUniform1f(loc(name), v); }
    void set2f(String name, float x, float y) { GL20.glUniform2f(loc(name), x, y); }
    void set3f(String name, float x, float y, float z) { GL20.glUniform3f(loc(name), x, y, z); }

    void delete() {
        GL20.glDeleteProgram(id);
    }
}
