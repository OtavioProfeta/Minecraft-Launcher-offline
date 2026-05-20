<p align="center">
<img src="resource/images/Global-Header_MCCB-Logo.png">
</p>

## Legal Notice
**This launcher is intended only for Minecraft Java Edition.**

This project is NOT affiliated with, endorsed, or sponsored by Mojang Studios or Microsoft.
"Minecraft" is a registered trademark of Mojang Studios.
This launcher is merely a third-party tool to facilitate the management of local Minecraft Java Edition installations.


Generate executable .jar:
```bash
  javac *.java
  cfm Launcher.jar manifest.txt *.class resource/
```

Generate JRE (Java executable .exe included):
```bash
  jdeps --print-module-deps --ignore-missing-deps Launcher.jar
  jlink --no-header-files --no-man-pages --compress=2 --strip-debug --add-modules java.base,java.desktop --output jre
```

It is still recommended that you have an original Minecraft Java Edition license from Mojang!
