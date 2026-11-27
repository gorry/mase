cmake_minimum_required(VERSION 3.22.1)
project(mase C)

# LAME（LGPL）。アプリ本体と分けた共有ライブラリ（libmp3lame.so）にする。
# 方針は memo/doc/design.md の「外のライブラリ」。
# LAME のソースは、リポジトリに入っていない。ビルドのときに、Gradle（app/build.gradle の fetchLame）が
# 配布元のソースパッケージを取ってきて、リポジトリ直下の third_party/ に取り出し、その場所を LAME_DIR で渡す。
if(NOT DEFINED LAME_DIR OR NOT EXISTS "${LAME_DIR}/libmp3lame/lame.c")
    message(FATAL_ERROR "LAME のソースが見つかりません（LAME_DIR=${LAME_DIR}）。gradlew から実行してください。")
endif()
add_library(mp3lame SHARED
        ${LAME_DIR}/libmp3lame/VbrTag.c
        ${LAME_DIR}/libmp3lame/bitstream.c
        ${LAME_DIR}/libmp3lame/encoder.c
        ${LAME_DIR}/libmp3lame/fft.c
        ${LAME_DIR}/libmp3lame/gain_analysis.c
        ${LAME_DIR}/libmp3lame/id3tag.c
        ${LAME_DIR}/libmp3lame/lame.c
        ${LAME_DIR}/libmp3lame/mpglib_interface.c
        ${LAME_DIR}/libmp3lame/newmdct.c
        ${LAME_DIR}/libmp3lame/presets.c
        ${LAME_DIR}/libmp3lame/psymodel.c
        ${LAME_DIR}/libmp3lame/quantize.c
        ${LAME_DIR}/libmp3lame/quantize_pvt.c
        ${LAME_DIR}/libmp3lame/reservoir.c
        ${LAME_DIR}/libmp3lame/set_get.c
        ${LAME_DIR}/libmp3lame/tables.c
        ${LAME_DIR}/libmp3lame/takehiro.c
        ${LAME_DIR}/libmp3lame/util.c
        ${LAME_DIR}/libmp3lame/vbrquantize.c
        ${LAME_DIR}/libmp3lame/version.c)
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H)
target_include_directories(mp3lame
        PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/lame-config ${LAME_DIR}/libmp3lame
        PUBLIC ${LAME_DIR}/include)
target_compile_options(mp3lame PRIVATE -w)
target_link_libraries(mp3lame m)

# Java（Mp3Encoder）から LAME を呼ぶための橋渡し
add_library(masemp3 SHARED mase_mp3.c)
target_link_libraries(masemp3 mp3lame)
