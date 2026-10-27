package com.rokid.glass.view.glsurface.opengl;

import android.opengl.GLES20;

import com.rokid.security.glass3.open.sdk.uitls.log.L;

public class GLShaderUtil {
    /**
     * 加载制定shader的方法
     * Load the specified shader.
     *
     * @param shaderType
     * @param source
     * @return
     */
    public static int loadShader
    (
            // shader的类型  GLES20.GL_VERTEX_SHADER   GLES20.GL_FRAGMENT_SHADER
            // Shader type: GLES20.GL_VERTEX_SHADER or GLES20.GL_FRAGMENT_SHADER.
            int shaderType,
            // shader的脚本字符串
            // Shader source string.
            String source
    ) {
        // 创建一个新shader
        // Create a new shader.
        int shader = GLES20.glCreateShader(shaderType);
        // 若创建成功则加载shader
        // Load the shader if creation succeeds.
        if (shader != 0) {
            // 加载shader的源代码
            // Load the shader source code.
            GLES20.glShaderSource(shader, source);
            // 编译shader
            // Compile the shader.
            GLES20.glCompileShader(shader);
            // 存放编译成功shader数量的数组
            // Array holding the shader compilation success status.
            int[] compiled = new int[1];
            // 获取Shader的编译情况
            // Get the shader compilation status.
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            // 若编译失败则显示错误日志并删除此shader
            // If compilation fails, log the error and delete the shader.
            if (compiled[0] == 0) {
                L.e("ES20_ERROR", "Could not compile shader " + shaderType + ":");
                L.e("ES20_ERROR", GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
        }
        return shader;
    }

    /**
     * 创建shader程序的方法
     * Create a shader program.
     *
     * @param vertexSource
     * @param fragmentSource
     * @return
     */
    public static int createProgram(String vertexSource, String fragmentSource) {
        // 加载顶点着色器
        // Load the vertex shader.
        int vertexShader = GLShaderUtil.loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }

        // 加载片元着色器
        // Load the fragment shader.
        int pixelShader = GLShaderUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            return 0;
        }

        // 创建程序
        // Create the program.
        int program = GLES20.glCreateProgram();
        // 若程序创建成功则向程序中加入顶点着色器与片元着色器
        // If program creation succeeds, add the vertex and fragment shaders.
        if (program != 0) {
            // 向程序中加入顶点着色器
            // Attach the vertex shader to the program.
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");
            // 向程序中加入片元着色器
            // Attach the fragment shader to the program.
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");
            // 链接程序
            // Link the program.
            GLES20.glLinkProgram(program);
            // 存放链接成功program数量的数组
            // Array holding the program link success status.
            int[] linkStatus = new int[1];
            // 获取program的链接情况
            // Get the program link status.
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            // 若链接失败则报错并删除程序
            // If linking fails, report the error and delete the program.
            if (linkStatus[0] != GLES20.GL_TRUE) {
                L.e("ES20_ERROR", "Could not link program: ");
                L.e("ES20_ERROR", GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }
        return program;
    }

    /**
     * 检查每一步操作是否有错误的方法
     * Check each operation for errors.
     *
     * @param op
     */
    public static void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            L.e("ES20_ERROR", op + ": glError " + error);
            throw new RuntimeException(op + ": glError " + error);
        }
    }


}
