# Third-party source and notices

The root GPL-3.0-only license applies to original AirAngelVL code. It does not replace the license notices in vendored source or Gradle-distributed dependencies. Preserve those notices when redistributing or modifying their files.

The vendored AndroidUSBCamera source is based on upstream tag **3.2.7**, commit **7fa7a99996e242a83c2c6258009b590d6ad51f8d**, from https://github.com/jiangdongguo/AndroidUSBCamera. AirAngelVL builds its modified UVC Java/JNI backend directly; the unfinished upstream application/rendering modules are not shipping dependencies. See [external/AndroidUSBCamera/AIRANGEL_CHANGES.md](external/AndroidUSBCamera/AIRANGEL_CHANGES.md).

| Component | Existing license and notice locations |
| --- | --- |
| AndroidUSBCamera / UVC Java and JNI wrapper | [Apache-2.0 LICENSE](external/AndroidUSBCamera/LICENSE); individual Saki/Serenegiant copyright headers remain in the source. |
| libusb | LGPL-2.1-or-later notices in source, including core.c; [license text](external/AndroidUSBCamera/libuvc/src/main/jni/libusb/COPYING). |
| libuvc | BSD-3-Clause notice retained in [stream.c](external/AndroidUSBCamera/libuvc/src/main/jni/libuvc/src/stream.c) and other source files. |
| libjpeg-turbo 1.5.0 | IJG, BSD-3-Clause and zlib terms as described in [LICENSE.md](external/AndroidUSBCamera/libuvc/src/main/jni/libjpeg-turbo-1.5.0/LICENSE.md); retain README.ijg and source notices. |
| RapidJSON and its retained third-party files | [MIT license](external/AndroidUSBCamera/libuvc/src/main/jni/rapidjson/license.txt); additional notices in thirdparty/jsoncpp/LICENSE and thirdparty/yajl/COPYING. |
| Retained LAME source (not in the shipping build) | LGPL-2.0-or-later header in libnative/src/main/cpp/module/mp3/lame/lame.c; [license text](external/AndroidUSBCamera/libnative/src/main/cpp/module/mp3/lame/COPYING). |
| Gradle wrapper and Android/Kotlin dependencies | Wrapper source notices and each dependency's published license continue to apply. The shipping dependency graph can be reproduced with Gradle as documented in BUILDING.md. |

This software is based in part on the work of the Independent JPEG Group.

The complete modified native sources used by this build are included. Old native prebuilt libraries, upstream demo APK/media files, Git metadata and generated build outputs are excluded from the public source snapshot; they are not needed by the shipping build. Keep corresponding source and required notices available when distributing compiled versions.
