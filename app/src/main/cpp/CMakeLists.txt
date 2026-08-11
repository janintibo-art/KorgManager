cmake_minimum_required(VERSION 3.22.1)
project(es12wav C)

# Les sources sont clonées par le workflow GitHub Actions dans ./es12wav
# (git clone https://github.com/polluxsynth/es12wav app/src/main/cpp/es12wav)
file(GLOB ES12WAV_SRC ${CMAKE_SOURCE_DIR}/es12wav/*.c)

# Astuce Android : un exécutable nommé lib*.so est empaqueté dans l'APK
# comme une bibliothèque, et peut ensuite être lancé depuis nativeLibraryDir.
add_executable(libes12wav.so ${ES12WAV_SRC})
target_compile_options(libes12wav.so PRIVATE -w -O2)
