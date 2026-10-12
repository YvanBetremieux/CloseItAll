package io.github.yvanbetremieux.closeitall;

interface IShellService {
    // Reserved by Shizuku to stop the user service.
    void destroy() = 16777114;

    // Runs `sh -c command`; returns [exitCode, stdout, stderr] (outputs truncated).
    String[] run(String command) = 1;

    // Runs `sh -c command` and streams stdout+stderr through a pipe (for large dumps).
    ParcelFileDescriptor stream(String command) = 2;
}
