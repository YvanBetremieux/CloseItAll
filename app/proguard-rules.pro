# Shizuku instantiates the UserService by class name inside the shell process.
-keep class io.github.yvanbetremieux.closeitall.shell.ShellService { *; }
-keep class io.github.yvanbetremieux.closeitall.IShellService { *; }
-keep class io.github.yvanbetremieux.closeitall.IShellService$* { *; }
